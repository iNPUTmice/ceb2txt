package im.conversations.ceb2txt.entities;


import com.google.common.base.Strings;


public class RtpSessionStatus {

    public final boolean successful;
    public final long duration;


    public RtpSessionStatus(boolean successful, long duration) {
        this.successful = successful;
        this.duration = duration;
    }

    public static RtpSessionStatus of(final String body) {
        final String[] parts = Strings.nullToEmpty(body).split(":", 2);
        long duration = 0;
        if (parts.length == 2) {
            try {
                duration = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                //do nothing
            }
        }
        boolean made;
        try {
            made = Boolean.parseBoolean(parts[0]);
        } catch (Exception e) {
            made = false;
        }
        return new RtpSessionStatus(made, duration);
    }

    @Override
    public String toString() {
        return successful + ":" + duration;
    }
}
