import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import net.sf.scuba.smartcards.CommandAPDU;
import net.sf.scuba.smartcards.ResponseAPDU;

public class Probe {
    public static void main(String[] args) throws Exception {
        Socket socket = new Socket("127.0.0.1", 8983);
        socket.setTcpNoDelay(true);
        Relay relay = new Relay(socket);
        System.out.println("== 1. SELECT MF (3F00) ==");
        relay.send("00A4000C023F00");
        System.out.println("== 2. SELECT EF.DIR (2F00) ==");
        String sw = relay.send("00A4000C022F00");
        if (sw.endsWith("9000") || sw.startsWith("61")) {
            System.out.println("== 3. READ EF.DIR ==");
            String dir = relay.readBinaryLoop();
            System.out.println("EF.DIR 原始: " + dir);
            for (String aid : parseAids(dir)) {
                System.out.println("== 4. SELECT AID " + aid + " ==");
                relay.send("00A4040C" + String.format("%02X", aid.length() / 2) + aid);
            }
        }
        System.out.println("== 5. SELECT eMRTD AID A0000002471001 ==");
        relay.send("00A4040C07A0000002471001");
        System.out.println("== 6. FID 扫描（鉴权前，区分 6A82 存在性与权限）==");
        for (int fid = 0x0101; fid <= 0x011F; fid++) {
            String select = relay.send(String.format("00A4000C02%04X", fid));
            if (!select.equals("6A82")) {
                String read = relay.send("00B0000008");
                System.out.println(String.format("FID %04X: SELECT SW=%s, READ=%s", fid, select, read));
            }
        }
        System.out.println("== 7. SFI 扫描（READ BINARY P2=0x80|SFI）==");
        for (int sfi = 1; sfi <= 31; sfi++) {
            String read = relay.send(String.format("00B000%02X08", 0x80 | sfi));
            if (!read.equals("6A82")) System.out.println("SFI " + sfi + ": " + read);
        }
        System.out.println("== 8. DG12/DG13 特殊选择尝试 ==");
        relay.send("00A4000C02010C");
        relay.send("00B0000008");
        relay.send("00A4000C02010D");
        relay.send("00B0000008");
        relay.send("QUIT");
        socket.close();
    }

    private static java.util.List<String> parseAids(String hex) {
        java.util.List<String> aids = new java.util.ArrayList<>();
        for (int index = 0; index + 4 <= hex.length() / 2; ) {
            int tag = Integer.parseUnsignedInt(hex.substring(index * 2, index * 2 + 2), 16);
            if (tag == 0x61) {
                int templateLength = Integer.parseUnsignedInt(hex.substring(index * 2 + 2, index * 2 + 4), 16);
                String template = hex.substring(index * 2 + 4, index * 2 + 4 + templateLength * 2);
                int pos = 0;
                while (pos + 2 <= template.length() / 2) {
                    int innerTag = Integer.parseUnsignedInt(template.substring(pos * 2, pos * 2 + 2), 16);
                    int length = Integer.parseUnsignedInt(template.substring(pos * 2 + 2, pos * 2 + 4), 16);
                    if (innerTag == 0x4F) aids.add(template.substring(pos * 2 + 4, pos * 2 + 4 + length * 2));
                    pos += 2 + length;
                }
                index += 2 + templateLength;
            } else index++;
        }
        return aids;
    }

    static class Relay {
        private final java.io.BufferedReader in;
        private final java.io.BufferedWriter out;
        Relay(Socket socket) throws Exception {
            in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            out = new java.io.BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
        }
        String send(String hex) throws Exception {
            out.write(hex); out.write("\n"); out.flush();
            String line = in.readLine();
            if (line == null) throw new IllegalStateException("连接中断");
            line = line.trim();
            if (line.startsWith("ERROR")) { System.out.println("  " + hex + " -> " + line); throw new IllegalStateException(line); }
            System.out.println("  " + hex + " -> " + line);
            return line;
        }
        String readBinaryLoop() throws Exception {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int offset = 0;
            while (offset < 512) {
                String response = send(String.format("00B0%02X%02X20", (offset >> 8) & 0x7F, offset & 0xFF));
                byte[] data = dataOf(response);
                if (data.length == 0) break;
                buffer.write(data);
                offset += data.length;
                int sw = statusOf(response);
                if (sw == 0x6B00 || sw == 0x6282 || sw == 0x6A82) break;
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : buffer.toByteArray()) hex.append(String.format("%02X", value));
            return hex.toString();
        }
        static byte[] dataOf(String response) {
            byte[] bytes = hexToBytes(response);
            return bytes.length <= 2 ? new byte[0] : java.util.Arrays.copyOf(bytes, bytes.length - 2);
        }
        static int statusOf(String response) {
            byte[] bytes = hexToBytes(response);
            return bytes.length < 2 ? 0xFFFF : ((bytes[bytes.length - 2] & 0xFF) << 8) | (bytes[bytes.length - 1] & 0xFF);
        }
        static byte[] hexToBytes(String text) {
            byte[] result = new byte[text.length() / 2];
            for (int index = 0; index < result.length; index++) {
                result[index] = (byte) ((Character.digit(text.charAt(index * 2), 16) << 4) | Character.digit(text.charAt(index * 2 + 1), 16));
            }
            return result;
        }
    }
}
