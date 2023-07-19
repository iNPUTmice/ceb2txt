package im.conversations.ceb2txt.entities;

import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;

public class Conversation {

    private String uuid;
    private int mode;
    private String contactJid;

    public String getUuid() {
        return uuid;
    }

    public Jid getContact() {
        return JidCreate.fromOrThrowUnchecked(contactJid);
    }

    public boolean isGroupChat() {
        return mode == 1;
    }
}
