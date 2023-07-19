package im.conversations.ceb2txt;

import com.google.common.base.MoreObjects;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;

public class BackupFileHeader {

    private static final int VERSION = 1;

    private final String app;
    private final Jid jid;
    private final long timestamp;
    private final byte[] iv;
    private final byte[] salt;

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("app", app)
                .add("jid", jid)
                .add("timestamp", timestamp)
                .add("iv", iv)
                .add("salt", salt)
                .toString();
    }

    public BackupFileHeader(String app, Jid jid, long timestamp, byte[] iv, byte[] salt) {
        this.app = app;
        this.jid = jid;
        this.timestamp = timestamp;
        this.iv = iv;
        this.salt = salt;
    }

    public void write(DataOutputStream dataOutputStream) throws IOException {
        dataOutputStream.writeInt(VERSION);
        dataOutputStream.writeUTF(app);
        dataOutputStream.writeUTF(jid.asBareJid().toString());
        dataOutputStream.writeLong(timestamp);
        dataOutputStream.write(iv);
        dataOutputStream.write(salt);
    }

    public static BackupFileHeader read(DataInputStream inputStream) throws IOException {
        final int version = inputStream.readInt();
        if (version > VERSION) {
            throw new IllegalArgumentException(
                    "Backup File version was "
                            + version
                            + " but app only supports up to version "
                            + VERSION);
        }
        String app = inputStream.readUTF();
        String jid = inputStream.readUTF();
        long timestamp = inputStream.readLong();
        byte[] iv = new byte[12];
        inputStream.readFully(iv);
        byte[] salt = new byte[16];
        inputStream.readFully(salt);

        return new BackupFileHeader(app, JidCreate.fromOrThrowUnchecked(jid), timestamp, iv, salt);
    }

    public byte[] getSalt() {
        return salt;
    }

    public byte[] getIv() {
        return iv;
    }

    public Jid getJid() {
        return jid;
    }

    public String getApp() {
        return app;
    }

    public long getTimestamp() {
        return timestamp;
    }

    private static final char[] hexArray = "0123456789abcdef".toCharArray();

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}
