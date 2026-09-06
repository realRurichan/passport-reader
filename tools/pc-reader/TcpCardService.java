import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.CommandAPDU;
import net.sf.scuba.smartcards.ResponseAPDU;

public class TcpCardService extends CardService {
    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;

    public TcpCardService(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
    }

    @Override public void open() {}

    @Override public boolean isOpen() { return socket.isConnected() && !socket.isClosed(); }

    @Override public ResponseAPDU transmit(CommandAPDU command) throws CardServiceException {
        try {
            out.write(hex(command.getBytes()));
            out.write("\n");
            out.flush();
            String line = in.readLine();
            if (line == null) throw new CardServiceException("中继连接已中断");
            line = line.trim();
            if (line.isEmpty()) throw new CardServiceException("空响应");
            if (line.startsWith("ERROR")) throw new CardServiceException(line);
            return new ResponseAPDU(hexToBytes(line));
        } catch (IOException error) {
            throw new CardServiceException(error.getMessage());
        }
    }

    @Override public byte[] getATR() { return new byte[0]; }

    @Override public void close() {
        try { out.write("QUIT\n"); out.flush(); } catch (IOException ignored) {}
        try { socket.close(); } catch (IOException ignored) {}
    }

    @Override public boolean isConnectionLost(Exception error) {
        return !(error instanceof CardServiceException && ((CardServiceException) error).getSW() != CardServiceException.SW_NONE);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02X", value));
        return result.toString();
    }

    private static byte[] hexToBytes(String text) {
        String clean = text.replace(" ", "");
        if (clean.length() % 2 != 0) throw new IllegalArgumentException("奇数长度十六进制：" + text);
        byte[] result = new byte[clean.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) ((Character.digit(clean.charAt(index * 2), 16) << 4) | Character.digit(clean.charAt(index * 2 + 1), 16));
        }
        return result;
    }
}
