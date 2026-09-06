import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.sf.scuba.smartcards.CardService;
import org.jmrtd.BACKey;
import org.jmrtd.PassportService;
import org.jmrtd.lds.CardAccessFile;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.SODFile;
import org.jmrtd.lds.icao.COMFile;
import org.jmrtd.lds.icao.DG11File;
import org.jmrtd.lds.icao.DG12File;
import org.jmrtd.lds.icao.DG1File;

public class PermitDump {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法：PermitDump <证件号码> <出生日期YYMMDD> <有效期YYMMDD> [输出目录]");
            System.exit(2);
        }
        String documentNumber = args[0].toUpperCase().replace("<", "");
        String birthDate = args[1];
        String expiryDate = args[2];
        Path outDir = Paths.get(args.length > 3 ? args[3] : "dump");
        Files.createDirectories(outDir);
        for (int attempt = 1; ; attempt++) {
            try {
                run(documentNumber, birthDate, expiryDate, outDir);
                return;
            } catch (Exception error) {
                System.out.println("尝试 " + attempt + " 失败：" + error.getMessage());
                if (attempt >= 5) throw error;
                System.out.println("请重新将证件贴紧手机 NFC 区域（3 秒后重试）…");
                Thread.sleep(3000);
            }
        }
    }

    private static void run(String documentNumber, String birthDate, String expiryDate, Path outDir) throws Exception {
        CardService cardService = new TcpCardService("127.0.0.1", 8983);
        PassportService service = new PassportService(cardService, 256, 224, false, false);
        cardService.open();
        service.open();
        BACKey key = new BACKey(documentNumber, birthDate, expiryDate);
        boolean pace = false;
        try {
            CardAccessFile access = new CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS));
            PACEInfo info = access.getSecurityInfos().stream().filter(PACEInfo.class::isInstance).map(PACEInfo.class::cast).findFirst().orElseThrow();
            System.out.println("PACE 可用（" + info.getObjectIdentifier() + "），正在执行 PACE…");
            service.doPACE(key, info.getObjectIdentifier(), PACEInfo.toParameterSpec(info.getParameterId()), null);
            service.sendSelectApplet(true);
            pace = true;
        } catch (Exception error) {
            System.out.println("PACE 不可用（" + error.getMessage() + "），改用 BAC…");
            service.sendSelectApplet(false);
            service.doBAC(key);
        }
        System.out.println("鉴权完成：" + (pace ? "PACE" : "BAC"));

        byte[] dg1 = readFile(service, PassportService.EF_DG1, outDir, "DG1");
        byte[] com = readFile(service, PassportService.EF_COM, outDir, "COM");
        byte[] sod = readFile(service, PassportService.EF_SOD, outDir, "SOD");
        for (short fileId = PassportService.EF_DG2; fileId <= PassportService.EF_DG16; fileId++) {
            readFile(service, fileId, outDir, "DG" + (fileId - PassportService.EF_DG1 + 1));
        }

        if (dg1 != null) {
            try {
                DG1File file = new DG1File(new ByteArrayInputStream(dg1));
                System.out.println("\n=== DG1 MRZ ===");
                System.out.println(file.getMRZInfo().toString());
            } catch (Exception error) { System.out.println("DG1 解析失败：" + error); }
        }
        if (com != null) {
            try {
                COMFile file = new COMFile(new ByteArrayInputStream(com));
                System.out.println("COM 声明的数据组：" + file.getTagList());
            } catch (Exception error) { System.out.println("COM 解析失败：" + error); }
        }
        if (sod != null) {
            try {
                SODFile file = new SODFile(new ByteArrayInputStream(sod));
                System.out.println("SOD 签名的数据组：" + file.getDataGroupHashes().keySet());
            } catch (Exception error) { System.out.println("SOD 解析失败：" + error); }
        }
        Path dg11Path = outDir.resolve("DG11.bin");
        if (Files.exists(dg11Path)) {
            try {
                DG11File file = new DG11File(new ByteArrayInputStream(Files.readAllBytes(dg11Path)));
                System.out.println("\n=== DG11 ===");
                print("完整姓名", file.getNameOfHolder());
                print("完整出生日期", file.getFullDateOfBirth());
                print("出生地", file.getPlaceOfBirth() == null ? null : String.join(" / ", file.getPlaceOfBirth()));
                print("住址", file.getPermanentAddress() == null ? null : String.join(" / ", file.getPermanentAddress()));
                print("个人概要", file.getPersonalSummary());
            } catch (Exception error) { System.out.println("DG11 解析失败：" + error); }
        }
        Path dg12Path = outDir.resolve("DG12.bin");
        if (Files.exists(dg12Path)) {
            try {
                DG12File file = new DG12File(new ByteArrayInputStream(Files.readAllBytes(dg12Path)));
                System.out.println("\n=== DG12（签注与备注）===");
                print("签发机关", file.getIssuingAuthority());
                print("签发日期", file.getDateOfIssue());
                print("签注与备注", file.getEndorsementsAndObservations());
                print("出境要求", file.getTaxOrExitRequirements());
            } catch (Exception error) { System.out.println("DG12 解析失败：" + error); }
        }
        Path dg13Path = outDir.resolve("DG13.bin");
        if (Files.exists(dg13Path)) {
            byte[] bytes = Files.readAllBytes(dg13Path);
            System.out.println("\n=== DG13 原始数据（" + bytes.length + " 字节）===");
            System.out.println(hexDump(bytes, 2048));
            System.out.println("--- 文本尝试（UTF-8）---");
            System.out.println(printable(new String(bytes, Charset.forName("UTF-8"))));
            System.out.println("--- 文本尝试（GB2312）---");
            System.out.println(printable(new String(bytes, Charset.forName("GB2312"))));
        }
        service.close();
        cardService.close();
    }

    private static byte[] readFile(PassportService service, short fileId, Path outDir, String name) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = service.getInputStream(fileId)) { input.transferTo(buffer); }
            byte[] bytes = buffer.toByteArray();
            Files.write(outDir.resolve(name + ".bin"), bytes);
            System.out.println(name + "：已读取 " + bytes.length + " 字节");
            return bytes;
        } catch (Exception error) {
            System.out.println(name + "：不可访问（" + error.getMessage() + "）");
            return null;
        }
    }

    private static void print(String label, String value) {
        if (value != null && !value.trim().isEmpty()) System.out.println(label + "：" + value.trim());
    }

    private static String printable(String text) {
        String cleaned = text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ");
        return cleaned.isBlank() ? "（无可打印内容）" : cleaned.trim();
    }

    private static String hexDump(byte[] bytes, int limit) {
        StringBuilder result = new StringBuilder();
        int shown = Math.min(bytes.length, limit);
        for (int index = 0; index < shown; index += 16) {
            result.append(String.format("%04X  ", index));
            for (int offset = index; offset < Math.min(index + 16, shown); offset++) result.append(String.format("%02X ", bytes[offset]));
            result.append('\n');
        }
        if (bytes.length > limit) result.append("…（截断）");
        return result.toString();
    }
}
