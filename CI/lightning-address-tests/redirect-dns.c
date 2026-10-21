// Test-process-only socket routing. CDK still performs TLS certificate checks,
// invoice parsing and full DNSSEC proof verification. Never link into the app.
#include <arpa/inet.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <string.h>

static int fixture_connect(int fd, const struct sockaddr *address, socklen_t length) {
    const char *enabled = getenv("LIGHTNING_DNS_STUB");
    if (enabled && !strcmp(enabled, "1")) {
        unsigned short port = 0;
        if (address->sa_family == AF_INET) port = ntohs(((const struct sockaddr_in *)address)->sin_port);
        if (address->sa_family == AF_INET6) port = ntohs(((const struct sockaddr_in6 *)address)->sin6_port);
        int destination = port == 443 ? 18445 : 0;
        if (address->sa_family == AF_INET && port == 53) {
            uint32_t host = ntohl(((const struct sockaddr_in *)address)->sin_addr.s_addr);
            if (host == 0x08080808 || host == 0x08080404) destination = 15353;
        }
        if (destination) {
            struct sockaddr_in fixture = {0};
            fixture.sin_len = sizeof(fixture);
            fixture.sin_family = AF_INET;
            fixture.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            fixture.sin_port = htons(destination);
            return connect(fd, (const struct sockaddr *)&fixture, sizeof(fixture));
        }
    }
    return connect(fd, address, length);
}
__attribute__((used)) static struct { const void *replacement; const void *original; }
interpose_connect __attribute__((section("__DATA,__interpose"))) = { (const void *)fixture_connect, (const void *)connect };
