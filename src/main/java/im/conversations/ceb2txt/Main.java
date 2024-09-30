package im.conversations.ceb2txt;

import com.google.common.base.Strings;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import im.conversations.ceb2txt.entities.Account;
import im.conversations.ceb2txt.entities.Conversation;
import im.conversations.ceb2txt.entities.Message;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.conscrypt.Conscrypt;
import org.jxmpp.stringprep.libidn.LibIdnXmppStringprep;
import org.sql2o.Connection;
import org.sql2o.Query;
import org.sql2o.Sql2o;

public class Main {

    private static final Collection<String> TABLE_ALLOW_LIST =
            Arrays.asList(
                    "accounts",
                    "conversations",
                    "messages",
                    "prekeys",
                    "signed_prekeys",
                    "sessions",
                    "identities");
    private static final Pattern COLUMN_PATTERN = Pattern.compile("^[a-zA-Z_]+$");

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
                + " bodyLanguage text, reactions text, occupantId number)";
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

    static {
        LibIdnXmppStringprep.setup();
    }

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
        } catch (final Exception e) {
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

        final Sql2o database = new Sql2o("sqlite:", null, null);

        final Connection connection = database.open();
        connection.createQuery(CREATE_ACCOUNTS_TABLE).executeUpdate();
        connection.createQuery(CREATE_CONVERSATIONS_TABLE).executeUpdate();
        connection.createQuery(CREATE_MESSAGES_TABLE).executeUpdate();
        connection.createQuery(CREATE_PREKEYS_TABLE).executeUpdate();
        connection.createQuery(CREATE_SIGNED_PREKEYS_TABLE).executeUpdate();
        connection.createQuery(CREATE_SESSIONS_TABLE).executeUpdate();
        connection.createQuery(CREATE_IDENTITIES_TABLE).executeUpdate();

        if (backupFileHeader.getVersion() == 1) {
            importV1Backup(connection, reader);
        } else if (backupFileHeader.getVersion() == 2) {
            importV2Backup(connection, reader);
        } else {
            throw new IllegalStateException("Unknown backup version");
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
                                    account.getJid().asBareJid().toString()
                                            + "/"
                                            + (group ? "group" : "1on1")
                                            + "/"
                                            + conversation.getContact().asBareJid().toString()
                                            + "/"
                                            + currentDate
                                            + ".txt");
                    conversationFile.getParentFile().mkdirs();
                    writer = new PrintWriter(conversationFile);
                }
                final String nick =
                        group ? message.getCounterpart().getResourceOrEmpty().toString() : "";
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
                        + account.getJid().asBareJid().toString()
                        + "/*/*.txt");
    }

    private static void importV1Backup(final Connection connection, final BufferedReader reader)
            throws IOException {
        String line;
        StringBuilder multiLineQuery = null;
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
    }

    private static void importV2Backup(final Connection connection, final BufferedReader reader)
            throws IOException {
        final JsonReader jsonReader = new JsonReader(reader);
        if (jsonReader.peek() == JsonToken.BEGIN_ARRAY) {
            jsonReader.beginArray();
        } else {
            throw new IllegalStateException("Backup file did not begin with array");
        }
        while (jsonReader.hasNext()) {
            if (jsonReader.peek() == JsonToken.BEGIN_OBJECT) {
                importRow(connection, jsonReader);
            } else if (jsonReader.peek() == JsonToken.END_ARRAY) {
                jsonReader.endArray();
                continue;
            }
        }
    }

    private static void importRow(final Connection connection, final JsonReader jsonReader)
            throws IOException {
        jsonReader.beginObject();
        final String firstParameter = jsonReader.nextName();
        if (!firstParameter.equals("table")) {
            throw new IllegalStateException("Expected key 'table'");
        }
        final String table = jsonReader.nextString();
        if (!TABLE_ALLOW_LIST.contains(table)) {
            throw new IOException(String.format("%s is not recognized for import", table));
        }
        final HashMap<String, Object> contentValues = new HashMap<>();
        final String secondParameter = jsonReader.nextName();
        if (!secondParameter.equals("values")) {
            throw new IllegalStateException("Expected key 'values'");
        }
        jsonReader.beginObject();
        while (jsonReader.peek() != JsonToken.END_OBJECT) {
            final String name = jsonReader.nextName();
            if (COLUMN_PATTERN.matcher(name).matches()) {
                if (jsonReader.peek() == JsonToken.NULL) {
                    jsonReader.nextNull();
                    contentValues.put(name, null);
                } else if (jsonReader.peek() == JsonToken.NUMBER) {
                    contentValues.put(name, jsonReader.nextLong());
                } else {
                    contentValues.put(name, jsonReader.nextString());
                }
            } else {
                throw new IOException(String.format("Unexpected column name %s", name));
            }
        }
        jsonReader.endObject();
        jsonReader.endObject();
        final Query query =
                connection.createQuery(
                        String.format(
                                "INSERT INTO %s (%s) VALUES (%s)",
                                table,
                                contentValues.keySet().stream().collect(Collectors.joining(", ")),
                                contentValues.keySet().stream()
                                        .map(k -> ":" + k)
                                        .collect(Collectors.joining(", "))));
        for (Map.Entry<String, Object> entry : contentValues.entrySet()) {
            query.addParameter(entry.getKey(), entry.getValue());
        }
        query.executeUpdate();
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
