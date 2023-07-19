package im.conversations.ceb2txt.entities;

import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Domainpart;
import org.jxmpp.jid.parts.Localpart;
import org.jxmpp.jid.parts.Resourcepart;

public class Account {

    private String username;
    private String server;
    private String resource;
    private String uuid;

    public Jid getJid() {
        return JidCreate.entityFullFrom(
                Localpart.fromOrNull(username),
                Domainpart.fromOrThrowUnchecked(server),
                Resourcepart.fromOrNull(resource));
    }

    public String getUuid() {
        return uuid;
    }
}
