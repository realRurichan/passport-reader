import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CommandAPDU;
import net.sf.scuba.smartcards.ResponseAPDU;
import org.jmrtd.BACKey;
import org.jmrtd.PassportService;

public class RecordProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) { System.err.println("Usage: RecordProbe <docnum> <dob> <expiry>"); System.exit(2); }
        CardService cardService = new TcpCardService("127.0.0.1", 8983);
        PassportService service = new PassportService(cardService, 256, 224, false, false);
        cardService.open();
        service.open();
        service.sendSelectApplet(false);
        service.doBAC(new BACKey(args[0].toUpperCase().replace("<", ""), args[1], args[2]));
        System.out.println("BAC done");

        java.io.ByteArrayOutputStream warm = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream input = service.getInputStream(PassportService.EF_DG1)) { input.transferTo(warm); }
        System.out.println("warmup DG1: " + warm.size() + " bytes");

        int[] fids = { 0x0111, 0x0112, 0x0113, 0x0114, 0x0115 };
        for (int fid : fids) {
            System.out.println("\n=== FID " + String.format("%04X", fid) + " ===");
            ResponseAPDU select = transceive(service, String.format("00A4020C02%04X", fid));
            System.out.println("SELECT(P1=02,SM): " + hex(select.getBytes()));
            if (select.getSW() != 0x9000 && select.getSW() >> 8 != 0x61) continue;
            String le = fid == 0x0111 ? "4F" : "4C";
            for (int record = 1; record <= 5; record++) {
                try {
                    ResponseAPDU response = transceive(service, String.format("00B2%02X04%s", record, le));
                    if (response.getSW() == 0x6B00 || response.getSW() == 0x6A83) break;
                    System.out.println("  RECORD " + record + " -> " + hex(response.getBytes()));
                    if (response.getSW() == 0x6A86 || response.getSW() == 0x6A82 || response.getSW() == 0x6981) break;
                } catch (Exception error) {
                    System.out.println("  RECORD " + record + " -> error " + error.getMessage());
                    break;
                }
            }
        }
        service.close();
        cardService.close();
        System.exit(0);
    }

    private static ResponseAPDU transceive(PassportService service, String hexCommand) throws Exception {
        byte[] bytes = new byte[hexCommand.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) ((Character.digit(hexCommand.charAt(index * 2), 16) << 4) | Character.digit(hexCommand.charAt(index * 2 + 1), 16));
        }
        return service.getSecureMessagingAPDUSender().transmit(service.getWrapper(), new CommandAPDU(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02X", value));
        return result.length() > 260 ? result.substring(0, 260) : result.toString();
    }
}
