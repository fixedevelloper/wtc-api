package com.wtc.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.shared.storage.FileTypes.Kind;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FileTypesTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length + 16];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    void recognizesJpegPngAndPdfBySignature() {
        assertThat(FileTypes.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).contains(Kind.JPEG);
        assertThat(FileTypes.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)))
                .contains(Kind.PNG);
        assertThat(FileTypes.detect("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)))
                .contains(Kind.PDF);
        assertThat(Kind.PDF.isImage()).isFalse();
        assertThat(Kind.JPEG.mediaType()).isEqualTo("image/jpeg");
    }

    @Test
    void rejectsEverythingElseWhateverTheClientClaims() {
        assertThat(
                        FileTypes.detect(
                                "<html><script>alert(1)</script></html>"
                                        .getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        assertThat(FileTypes.detect("MZ executable".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(FileTypes.detect("GIF89a....".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(FileTypes.detect(new byte[0])).isEmpty();
        assertThat(FileTypes.detect(null)).isEmpty();
        assertThat(FileTypes.detect(bytes(0xFF, 0xD8))).as("signature JPEG incomplète").isEmpty();
    }
}
