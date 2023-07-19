package im.conversations.ceb2txt.entities;

import com.google.common.base.Strings;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;

public class Message {

    // constants copy pasted from Conversations
    public static final int TYPE_TEXT = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_FILE = 2;
    public static final int TYPE_STATUS = 3;
    public static final int TYPE_PRIVATE = 4;
    public static final int TYPE_PRIVATE_FILE = 5;
    public static final int TYPE_RTP_SESSION = 6;

    // more constants copy pasted from Conversations
    public static final int STATUS_RECEIVED = 0;
    public static final int STATUS_UNSEND = 1;
    public static final int STATUS_SEND = 2;
    public static final int STATUS_SEND_FAILED = 3;
    public static final int STATUS_WAITING = 5;
    public static final int STATUS_OFFERED = 6;
    public static final int STATUS_SEND_RECEIVED = 7;
    public static final int STATUS_SEND_DISPLAYED = 8;

    private long timeSent;
    private int status;
    private String body;
    private int type;
    private String counterpart;

    public long getTimeSent() {
        return timeSent;
    }

    public boolean isReceived() {
        return status == 0;
    }

    public String getBody() {
        if (isFileOrImage()) {
            final FileParams fileParams = getFileParams();
            if (Strings.isNullOrEmpty(fileParams.url)) {
                return "[file received over P2P (Jingle)]";
            } else {
                return fileParams.url;
            }
        } else if (type == TYPE_RTP_SESSION) {
            final RtpSessionStatus rtpSessionStatus = RtpSessionStatus.of(body);
            if (rtpSessionStatus.successful) {
                if (status == STATUS_RECEIVED) {
                    return rtpSessionStatus.duration <= 0
                            ? "Incoming call"
                            : String.format(
                                    "Incoming call. Duration %d seconds",
                                    rtpSessionStatus.duration / 1000);
                } else {
                    return rtpSessionStatus.duration <= 0
                            ? "Outgoing call "
                            : String.format(
                                    "Outgoing call. Duration %d seconds",
                                    rtpSessionStatus.duration / 1000);
                }
            } else {
                return "Missed call";
            }
        } else {
            return body;
        }
    }

    private boolean isFileOrImage() {
        return type == TYPE_FILE || type == TYPE_IMAGE || type == TYPE_PRIVATE_FILE;
    }

    public FileParams getFileParams() {
        final FileParams fileParams = new FileParams();
        final String[] parts = body == null ? new String[0] : body.split("\\|");
        switch (parts.length) {
            case 1:
                try {
                    fileParams.size = Long.parseLong(parts[0]);
                } catch (NumberFormatException e) {
                    fileParams.url = parts[0];
                }
                break;
            case 5:
                fileParams.runtime = parseInt(parts[4]);
            case 4:
                fileParams.width = parseInt(parts[2]);
                fileParams.height = parseInt(parts[3]);
            case 2:
                fileParams.url = parts[0];
                fileParams.size = parseLong(parts[1]);
                break;
            case 3:
                fileParams.size = parseLong(parts[0]);
                fileParams.width = parseInt(parts[1]);
                fileParams.height = parseInt(parts[2]);
                break;
        }
        return fileParams;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public Jid getCounterpart() {
        return JidCreate.fromOrNull(counterpart);
    }

    private static class FileParams {
        public String url;
        public long size = 0;
        public int width = 0;
        public int height = 0;
        public int runtime = 0;
    }
}
