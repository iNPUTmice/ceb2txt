package im.conversations.ceb2txt.entities;

import rocks.xmpp.addr.Jid;

public class Account {

    private String username;
    private String server;
    private String resource;
    private String uuid;

    public Jid getJid() {
        return Jid.of(username, server, resource);
    }

    public String getUuid() {
        return uuid;
    }
}
