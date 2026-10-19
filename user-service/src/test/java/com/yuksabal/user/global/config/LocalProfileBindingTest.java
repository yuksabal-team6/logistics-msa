package com.yuksabal.user.global.config;

import com.yuksabal.user.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// local 프로필이면 loopback에만 바인딩되어 같은 PC에서만 접속된다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "local"})
class LocalProfileBindingTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final int CONNECT_TIMEOUT_MS = 2000;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> TestJwt.randomBase64Secret(32));
    }

    @LocalServerPort
    private int port;
    @Autowired
    private Environment environment;

    @Test
    void 바인딩_주소와_Eureka_등록_주소가_loopback이다() {
        // when & then
        assertThat(environment.getProperty("server.address")).isEqualTo(LOOPBACK);
        assertThat(environment.getProperty("eureka.instance.ip-address")).isEqualTo(LOOPBACK);
    }

    @Test
    void loopback으로는_접속된다() throws IOException {
        try (Socket socket = new Socket()) {
            // when
            socket.connect(new InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS);

            // then
            assertThat(socket.isConnected()).isTrue();
        }
    }

    @Test
    void LAN_주소로는_접속되지_않는다() throws IOException {
        // given
        Optional<InetAddress> lanAddress = findLanAddress();
        assumeTrue(lanAddress.isPresent(), "LAN 주소가 없는 환경에서는 건너뛴다");

        // when & then
        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(lanAddress.get(), port), CONNECT_TIMEOUT_MS);
            }
        }).isInstanceOf(IOException.class);
    }

    private static Optional<InetAddress> findLanAddress() throws SocketException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    return Optional.of(address);
                }
            }
        }
        return Optional.empty();
    }
}
