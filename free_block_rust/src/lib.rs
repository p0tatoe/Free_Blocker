uniffi::setup_scaffolding!();

mod proxy;
mod quic;

use proxy::{extract_dns_name, to_lowercase_wire_format, to_trie_key, create_null_response, create_forwarded_response, create_tcp_rst, create_icmp_unreachable, create_servfail_response};
use quic::DoqClient;
use radix_trie::Trie;
use std::sync::{Arc, RwLock};
use tokio::runtime::Runtime;
use tokio::sync::Mutex as TokioMutex;
use std::num::NonZeroUsize;
use tokio::sync::mpsc;
use std::time::{Instant, Duration};

struct CacheEntry {
    payload: Vec<u8>,
    expires_at: Instant,
}

fn get_min_ttl(payload: &[u8]) -> Option<u32> {
    if payload.len() < 12 { return None; }
    let qdcount = u16::from_be_bytes([payload[4], payload[5]]);
    let ancount = u16::from_be_bytes([payload[6], payload[7]]);
    let mut idx = 12;
    for _ in 0..qdcount {
        while idx < payload.len() {
            let len = payload[idx] as usize;
            if len == 0 { idx += 1; break; }
            if len & 0xC0 == 0xC0 { idx += 2; break; }
            idx += len + 1;
        }
        idx += 4;
    }
    let mut min_ttl = u32::MAX;
    for _ in 0..ancount {
        if idx >= payload.len() { break; }
        if payload[idx] & 0xC0 == 0xC0 {
            idx += 2;
        } else {
            while idx < payload.len() {
                let len = payload[idx] as usize;
                if len == 0 { idx += 1; break; }
                if len & 0xC0 == 0xC0 { idx += 2; break; }
                idx += len + 1;
            }
        }
        if idx + 10 > payload.len() { break; }
        let ttl = u32::from_be_bytes([payload[idx+4], payload[idx+5], payload[idx+6], payload[idx+7]]);
        if ttl < min_ttl { min_ttl = ttl; }
        let rdlength = u16::from_be_bytes([payload[idx+8], payload[idx+9]]) as usize;
        idx += 10 + rdlength;
    }
    if min_ttl == u32::MAX { None } else { Some(min_ttl) }
}

#[derive(uniffi::Object)]
pub struct DnsProxy {
    tun_fd: i32,
    upstream_host: String,
    sni_hostname: String,
    blocklist: Arc<RwLock<Trie<Vec<u8>, ()>>>,
    rt: Option<Runtime>,
    shutdown_tx: std::sync::Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
}

#[uniffi::export]
impl DnsProxy {
    #[uniffi::constructor]
    pub fn new(tun_fd: i32, upstream_host: String, sni_hostname: String) -> Arc<Self> {
        Arc::new(Self {
            tun_fd,
            upstream_host,
            sni_hostname,
            blocklist: Arc::new(RwLock::new(Trie::new())),
            rt: tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
                .or_else(|_| tokio::runtime::Runtime::new())
                .ok(),
            shutdown_tx: std::sync::Mutex::new(None),
        })
    }

    pub fn stop(&self) {
        let mut lock = self.shutdown_tx.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(tx) = lock.take() {
            let _ = tx.send(());
        }
    }

    pub fn start(&self) {
        let tun_fd = self.tun_fd;
        let upstream = self.upstream_host.clone();
        let sni_hostname = self.sni_hostname.clone();
        let blocklist = self.blocklist.clone();

        let (tx, rx) = tokio::sync::oneshot::channel();
        let mut lock = self.shutdown_tx.lock().unwrap_or_else(|e| e.into_inner());
        *lock = Some(tx);

        if let Some(rt) = &self.rt {
            rt.spawn(async move {
                tokio::select! {
                    _ = run_proxy(tun_fd, upstream, sni_hostname, blocklist) => {}
                    _ = rx => {} // shutdown signaled
                }
            });
        }
    }

    pub fn update_blocklist(&self, domains: Vec<String>) {
        let mut trie = Trie::new();
        for domain in domains {
            let wire_format = domain_to_wire_format(&domain);
            trie.insert(wire_format, ());
        }
        let mut lock = self.blocklist.write().unwrap_or_else(|e| e.into_inner());
        *lock = trie;
    }
}

/// Convert a domain string to a reversed, lowercased wire-format key for the trie.
/// "example.com" → \x03com\x07example  (no trailing \x00)
///
/// Reversed so that `get_ancestor_value` can match subdomains:
/// blocking "example.com" (key: \x03com\x07example) will match
/// a query for "ads.example.com" (key: \x03com\x07example\x03ads)
/// because the parent key is a byte-prefix of the child key.
fn domain_to_wire_format(domain: &str) -> Vec<u8> {
    let mut out = Vec::new();
    let parts: Vec<&str> = domain.split('.').filter(|p| !p.is_empty()).collect();
    for part in parts.iter().rev() {
        out.push(part.len() as u8);
        out.extend_from_slice(part.to_ascii_lowercase().as_bytes());
    }
    out
}

async fn run_proxy(tun_fd: i32, upstream: String, sni_hostname: String, blocklist: Arc<RwLock<Trie<Vec<u8>, ()>>>) {
    let mut buf = vec![0u8; 65536];
    let async_fd = match tokio::io::unix::AsyncFd::new(tun_fd) {
        Ok(fd) => fd,
        Err(_) => return,
    };
    
    let semaphore = Arc::new(tokio::sync::Semaphore::new(100));
    unsafe {
        let flags = libc::fcntl(tun_fd, libc::F_GETFL);
        libc::fcntl(tun_fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
    }
    
    let doq_pool: Arc<TokioMutex<Option<Arc<DoqClient>>>> = Arc::new(TokioMutex::new(None));
    let cache = Arc::new(TokioMutex::new(lru::LruCache::<Vec<u8>, CacheEntry>::new(NonZeroUsize::new(1000).unwrap())));

    let (tx, mut rx) = mpsc::channel::<Vec<u8>>(1000);

    loop {
        tokio::select! {
            Some(packet) = rx.recv() => {
                let _ = unsafe { libc::write(tun_fd, packet.as_ptr() as *mut libc::c_void, packet.len()) };
            }
            res = async_fd.readable() => {
                let mut guard = match res {
                    Ok(g) => g,
                    Err(_) => continue,
                };
        
        match unsafe { libc::read(tun_fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) } {
            n if n > 0 => {
                guard.retain_ready();
                let n = n as usize;
                let pkt = &buf[..n];
                
                if let Ok(sliced) = etherparse::SlicedPacket::from_ip(pkt) {
                    if let Some(etherparse::TransportSlice::Udp(udp)) = sliced.transport.as_ref() {
                        if udp.destination_port() == 53 {
                            let payload = udp.payload();
                            if let Some(qname) = extract_dns_name(payload) {
                                // Lowercased forward key for cache lookups
                                let mut cache_key = to_lowercase_wire_format(qname);
                                let mut idx = 12;
                                while idx < payload.len() {
                                    let len = payload[idx] as usize;
                                    if len == 0 { idx += 1; break; }
                                    idx += len + 1;
                                }
                                let full_cache_key = if idx + 2 <= payload.len() {
                                    cache_key.extend_from_slice(&payload[idx..idx+2]);
                                    Some(cache_key)
                                } else { None };

                                // Reversed key for blocklist trie lookups
                                let trie_key = to_trie_key(qname);
                                
                                let blocked = {
                                    let lock = blocklist.read().unwrap_or_else(|e| e.into_inner());
                                    lock.get_ancestor_value(&trie_key).is_some()
                                };
                                
                                if blocked {
                                    if let Some(resp) = create_null_response(&sliced, payload) {
                                        let _ = tx.try_send(resp);
                                    }
                                } else {
                                    // Check cache
                                    let mut cache_lock = cache.lock().await;
                                    let mut use_cache = false;
                                    if let Some(ck) = &full_cache_key {
                                        if let Some(cached_entry) = cache_lock.get(ck) {
                                            if Instant::now() < cached_entry.expires_at {
                                                if let Some(resp) = create_forwarded_response(&sliced, payload, &cached_entry.payload) {
                                                    let _ = tx.try_send(resp);
                                                }
                                                use_cache = true;
                                            }
                                        }
                                    }
                                    if use_cache {
                                        continue;
                                    }
                                    drop(cache_lock);
                                    
                                    // Forward via DoQ
                                    let doq_pool = doq_pool.clone();
                                    let payload_vec = payload.to_vec();
                                    let req_ip = pkt.to_vec();
                                    let cache_key = full_cache_key;
                                    let cache_clone = cache.clone();
                                    let upstream_clone = upstream.clone();
                                    let tx_clone = tx.clone();
                                    let sni_clone = sni_hostname.clone();
                                    
                                    if let Ok(permit) = semaphore.clone().try_acquire_owned() {
                                        tokio::spawn(async move {
                                            let _permit = permit;
                                            let mut retries = 2;
                                            while retries > 0 {
                                                retries -= 1;
                                                
                                                let doq_opt = doq_pool.lock().await.as_ref().filter(|c| c.is_alive()).cloned();
                                                let doq = if let Some(c) = doq_opt {
                                                    c
                                                } else {
                                                    if let Ok(Ok(c)) = tokio::time::timeout(std::time::Duration::from_secs(3), DoqClient::connect(&upstream_clone, &sni_clone)).await {
                                                        let arc = Arc::new(c);
                                                        *doq_pool.lock().await = Some(arc.clone());
                                                        arc
                                                    } else {
                                                        break;
                                                    }
                                                };
                                                
                                                let query_future = doq.send_query(&payload_vec);
                                                match tokio::time::timeout(std::time::Duration::from_secs(3), query_future).await {
                                                    Ok(Ok(resp_payload)) => {
                                                        if let Ok(sliced) = etherparse::SlicedPacket::from_ip(&req_ip) {
                                                            if let Some(resp) = create_forwarded_response(&sliced, &payload_vec, &resp_payload) {
                                                                let _ = tx_clone.try_send(resp);
                                                                if let Some(ck) = cache_key {
                                                                    if let Some(ttl) = get_min_ttl(&resp_payload) {
                                                                        if ttl > 0 {
                                                                            let mut lock = cache_clone.lock().await;
                                                                            lock.put(ck, CacheEntry {
                                                                                payload: resp_payload,
                                                                                expires_at: Instant::now() + Duration::from_secs(ttl as u64),
                                                                            });
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        break;
                                                    }
                                                    _ => {
                                                        // Timeout or Error from DoQ
                                                        let mut lock = doq_pool.lock().await;
                                                        *lock = None;
                                                        if retries == 0 {
                                                            if let Ok(sliced) = etherparse::SlicedPacket::from_ip(&req_ip) {
                                                                if let Some(resp) = create_servfail_response(&sliced, &payload_vec) {
                                                                    let _ = tx_clone.try_send(resp);
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        });
                                    } else {
                                        // Semaphore full: Return SERVFAIL immediately to prevent app hang
                                        if let Ok(sliced) = etherparse::SlicedPacket::from_ip(&req_ip) {
                                            if let Some(resp) = create_servfail_response(&sliced, &payload_vec) {
                                                let _ = tx_clone.try_send(resp);
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Drop non-DNS UDP packets to prevent ICMP amplification
                        }
                    } else if let Some(etherparse::TransportSlice::Tcp(_)) = sliced.transport.as_ref() {
                        if let Some(resp) = create_tcp_rst(&sliced) {
                            let _ = tx.try_send(resp);
                        }
                    }
                }
            }
            n if n < 0 => {
                let err = std::io::Error::last_os_error();
                if err.kind() == std::io::ErrorKind::WouldBlock {
                    guard.clear_ready();
                } else {
                    break;
                }
            }
            _ => break, // n == 0: EOF / TUN closed
        }
            } // end res = async_fd.readable()
        } // end tokio::select!
    } // end loop
}
