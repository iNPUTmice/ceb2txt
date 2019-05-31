package im.conversations.ceb2txt.entities;

import rocks.xmpp.addr.Jid;

public class Conversation {

    private String uuid;
    private int mode;
    private String contactJid;

    public String getUuid() {
        return uuid;
    }

    public Jid getContact() {
        return Jid.of(contactJid);
    }

    public boolean isGroupChat() {
        return mode == 1;
    }
}
