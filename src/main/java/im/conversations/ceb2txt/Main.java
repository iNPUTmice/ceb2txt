package im.conversations.ceb2txt;

import com.google.common.base.Strings;
import im.conversations.ceb2txt.entities.Account;
import im.conversations.ceb2txt.entities.Conversation;
import im.conversations.ceb2txt.entities.Message;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPInputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.conscrypt.Conscrypt;
import org.sql2o.Connection;
import org.sql2o.Sql2o;

public class Main {

    public static final String KEYTYPE = "AES";
    public static final String CIPHERMODE = "AES/GCM/NoPadding";

    private static final String CREATE_ACCOUNTS_TABLE =
            "create table accounts (uuid text primary key, username text, server text, password"
                    + " text, display_name text, status number, status_message text, rosterversion"
                    + " text, options number, avatar text, keys text, hostname text, port number,"
                    + " resource text, pinned_mechanism TEXT, pinned_channel_binding TEXT,"
                    + " fast_mechanism TEXT, fast_token TEXT)";
    private static final String CREATE_CONVERSATIONS_TABLE =
            "create table conversations (uuid text, accountUuid text, name text, contactUuid text,"
                + " contactJid text, created number, status number, mode number, attributes text)";
    private static final String CREATE_MESSAGES_TABLE =
            "create table messages (uuid text, conversationUuid text, timeSent number, counterpart"
                + " text, trueCounterpart text, body text, encryption number, status number, type"
                + " number, relativeFilePath text, serverMsgId text, axolotl_fingerprint text,"
                + " carbon number, edited number, read number, oob number, errorMsg text,"
                + " readByMarkers text, markable number, remoteMsgId text, deleted number,"
                + " bodyLanguage text)";
    private static final String CREATE_PREKEYS_TABLE =
            "create table prekeys (account text, id text, key text)";
    private static final String CREATE_SIGNED_PREKEYS_TABLE =
            "create table signed_prekeys (account text, id text, key text)";
    private static final String CREATE_SESSIONS_TABLE =
            "create table sessions (account text, name text, device_id text, key text)";
    private static final String CREATE_IDENTITIES_TABLE =
            "create table identities (account text, name text, ownkey text, fingerprint text,"
                    + " certificate text, trust number, active number, last_activation number, key"
                    + " text)";

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm");

    public static void main(final String... args) throws Exception {

        if (args.length != 1) {
            System.err.println("Usage java -jar im.conversations.ceb2txt-0.1.jar [filename]");
            System.exit(1);
        }

        final String cebFile = args[0];
        final File file = new File(cebFile);

        final FileInputStream fileInputStream = new FileInputStream(file);
        final DataInputStream dataInputStream = new DataInputStream(fileInputStream);
        final BackupFileHeader backupFileHeader;

        try {
            backupFileHeader = BackupFileHeader.read(dataInputStream);
        } catch (Exception e) {
            System.err.println(file.getAbsolutePath() + " does not seem to be a valid backup file");
            System.exit(1);
            return;
        }
        final Console console = System.console();

        final String password =
                new String(
                        console.readPassword(
                                "Enter password for "
                                        + backupFileHeader.getJid().asBareJid()
                                        + ": "));

        final Cipher cipher = Cipher.getInstance(CIPHERMODE, Conscrypt.newProvider());
        byte[] key = getKey(password, backupFileHeader.getSalt());

        final BufferedReader reader;

        try {
            final SecretKeySpec keySpec = new SecretKeySpec(key, KEYTYPE);
            final IvParameterSpec ivSpec = new IvParameterSpec(backupFileHeader.getIv());
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            final CipherInputStream cipherInputStream =
                    new CipherInputStream(fileInputStream, cipher);

            final GZIPInputStream gzipInputStream = new GZIPInputStream(cipherInputStream);
            reader =
                    new BufferedReader(
                            new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8));
        } catch (InvalidAlgorithmParameterException e) {
            System.err.println("Correct backup file");
            System.exit(1);
            return;
        } catch (IOException e) {
            System.err.println("Wrong password or corrupt backup file");
            System.exit(1);
            return;
        }
        String line;
        StringBuilder multiLineQuery = null;

        final Sql2o database = new Sql2o("sqlite:", null, null);

        final Connection connection = database.open();
        connection.createQuery(CREATE_ACCOUNTS_TABLE).executeUpdate();
        connection.createQuery(CREATE_CONVERSATIONS_TABLE).executeUpdate();
        connection.createQuery(CREATE_MESSAGES_TABLE).executeUpdate();
        connection.createQuery(CREATE_PREKEYS_TABLE).executeUpdate();
        connection.createQuery(CREATE_SIGNED_PREKEYS_TABLE).executeUpdate();
        connection.createQuery(CREATE_SESSIONS_TABLE).executeUpdate();
        connection.createQuery(CREATE_IDENTITIES_TABLE).executeUpdate();

        while ((line = reader.readLine()) != null) {
            int count = count(line, '\'');
            if (multiLineQuery != null) {
                multiLineQuery.append('\n');
                multiLineQuery.append(line);
                if (count % 2 == 1) {
                    connection.createQuery(multiLineQuery.toString()).executeUpdate();
                    multiLineQuery = null;
                }
            } else {
                if (count % 2 == 0) {
                    connection.createQuery(line).executeUpdate();
                } else {
                    multiLineQuery = new StringBuilder(line);
                }
            }
        }

        final Account account =
                connection
                        .createQuery("select uuid,username,server,resource from accounts limit 1")
                        .executeAndFetchFirst(Account.class);

        final List<Conversation> conversationList =
                connection
                        .createQuery(
                                "select uuid,mode,contactJid from conversations where"
                                        + " accountUuid=:uuid")
                        .addParameter("uuid", account.getUuid())
                        .executeAndFetch(Conversation.class);

        for (final Conversation conversation : conversationList) {
            final boolean group = conversation.isGroupChat();
            final List<Message> messageList =
                    connection
                            .createQuery(
                                    "select body,status,timeSent,counterpart,type from messages"
                                            + " where conversationUuid=:conversation")
                            .addParameter("conversation", conversation.getUuid())
                            .executeAndFetch(Message.class);
            PrintWriter writer = null;
            String currentDate = null;
            for (final Message message : messageList) {
                Date date = new Date(message.getTimeSent());
                if (currentDate == null || !currentDate.equals(DATE_FORMAT.format(date))) {
                    currentDate = DATE_FORMAT.format(date);
                    if (writer != null) {
                        writer.close();
                    }
                    File conversationFile =
                            new File(
                                    account.getJid().asBareJid().toEscapedString()
                                            + "/"
                                            + (group ? "group" : "1on1")
                                            + "/"
                                            + conversation
                                                    .getContact()
                                                    .asBareJid()
                                                    .toEscapedString()
                                            + "/"
                                            + currentDate
                                            + ".txt");
                    conversationFile.getParentFile().mkdirs();
                    writer = new PrintWriter(conversationFile);
                }
                final String nick =
                        group ? Strings.nullToEmpty(message.getCounterpart().getResource()) : "";
                writer.println(
                        TIME_FORMAT.format(date)
                                + " "
                                + nick
                                + (nick.length() > 0 ? " " : "")
                                + (message.isReceived() ? "<-" : "->")
                                + " "
                                + message.getBody()
                                        .replaceAll(
                                                "\n",
                                                "\n"
                                                        + Strings.repeat(
                                                                " ",
                                                                9
                                                                        + nick.length()
                                                                        + (nick.length() > 0
                                                                                ? 1
                                                                                : 0))));
            }
            if (writer != null) {
                writer.close();
            }
        }

        System.out.println(
                conversationList.size()
                        + " conversations have been written to "
                        + account.getJid().asBareJid().toEscapedString()
                        + "/*/*.txt");
    }

    public static byte[] getKey(final String password, final byte[] salt) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, 1024, 128))
                    .getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new AssertionError(e);
        }
    }

    private static int count(final String input, char c) {
        int count = 0;
        for (char aChar : input.toCharArray()) {
            if (aChar == c) {
                ++count;
            }
        }
        return count;
    }
}
