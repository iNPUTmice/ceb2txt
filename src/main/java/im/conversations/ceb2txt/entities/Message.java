package im.conversations.ceb2txt.entities;

import rocks.xmpp.addr.Jid;

public class Message {

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
        return body;
    }

    public Jid getCounterpart() {
        return Jid.of(counterpart);
    }
}
