use std::sync::Arc;
use quinn::Endpoint;
use std::os::fd::AsRawFd;

fn test() {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").unwrap();
    let fd = socket.as_raw_fd();
    let runtime = Arc::new(quinn::TokioRuntime);
    let mut endpoint = Endpoint::new(
        quinn::EndpointConfig::default(),
        None,
        socket,
        runtime,
    ).unwrap();
}
