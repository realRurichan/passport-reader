import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.sf.scuba.smartcards.CardService;
import org.jmrtd.BACKey;
import org.jmrtd.PassportService;

public class AuthProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法：AuthProbe <证件号码> <出生日期YYMMDD> <有效期YYMMDD> [输出目录]");
            System.exit(2);
        }
        Path outDir = Paths.get(args.length > 3 ? args[3] : "probe");
        Files.createDirectories(outDir);
        CardService cardService = new TcpCardService("127.0.0.1", 8983);
        PassportService service = new PassportService(cardService, 256, 224, false, false);
        cardService.open();
        service.open();
        service.sendSelectApplet(false);
        service.doBAC(new BACKey(args[0].toUpperCase().replace("<", ""), args[1], args[2]));
        System.out.println("BAC 完成");

        System.out.println("== SELECT MF ==");
        try { service.sendSelectMF(); System.out.println("MF: 9000"); }
        catch (Exception error) { System.out.println("MF: " + error.getMessage()); }

        System.out.println("== EF.DIR (2F00) under SM ==");
        tryRead(service, (short) 0x2F00, outDir, "EFDIR");

        int[] specials = { 0x3F00, 0x0002, 0x0003, 0x2F01, 0x2F02, 0x2F03, 0x2F05, 0x2F06, 0x1F00, 0x1F01, 0xEF01, 0xEF02, 0x9001, 0x9F01 };
        System.out.println("== FID 扫描 0101-0150 + 特殊 ==");
        for (int fid = 0x0101; fid <= 0x0150; fid++) tryRead(service, (short) fid, outDir, String.format("FID%04X", fid));
        for (int fid : specials) tryRead(service, (short) fid, outDir, String.format("FID%04X", fid));
        System.out.println("扫描完成，输出目录 " + outDir.toAbsolutePath());
        service.close();
        cardService.close();
        System.exit(0);
    }

    private static void tryRead(PassportService service, short fileId, Path outDir, String name) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = service.getInputStream(fileId)) { input.transferTo(buffer); }
            byte[] bytes = buffer.toByteArray();
            Files.write(outDir.resolve(name + ".bin"), bytes);
            System.out.println(String.format("%s: %d 字节 %s", name, bytes.length, preview(bytes)));
        } catch (Exception error) {
            String message = error.getMessage();
            if (message != null && message.length() > 120) message = message.substring(0, 120);
            System.out.println(name + ": 不可读（" + message + "）");
        }
    }

    private static String preview(byte[] bytes) {
        StringBuilder result = new StringBuilder("[");
        int shown = Math.min(bytes.length, 32);
        for (int index = 0; index < shown; index++) result.append(String.format("%02X", bytes[index]));
        return result.append("]").toString();
    }
}
