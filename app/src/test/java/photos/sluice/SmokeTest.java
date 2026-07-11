package photos.sluice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "sluice.paths.repo-root=.",
        "sluice.paths.library-root=.",
        "sluice.paths.inbox=."
})
class SmokeTest {

    @Test
    void contextLoads() {
    }
}
