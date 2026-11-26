package com.leo.trashvpn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Importable profiles. Call only after a user requests a clipboard export. */
final class NodeExport {
    enum Format {
        CLASH("Clash"), V2RAYN("v2rayN"), SING_BOX("sing-box");
        final String label;
        Format(String label) { this.label=label; }
        static Format[] forProtocol(WarpProfile.Protocol protocol) {
            return protocol==WarpProfile.Protocol.WG?values():new Format[]{CLASH};
        }
    }

    private NodeExport() { }

    static String render(WarpProfile.Protocol protocol,List<NodeSelection.Score> cached,Format format,
                         TunnelIdentity identity,NetworkSettings settings) throws JSONException {
        if(format!=Format.CLASH && protocol!=WarpProfile.Protocol.WG)
            throw new IllegalArgumentException("This export format supports WireGuard only");
        Set<String> endpoints=new LinkedHashSet<>();
        for(NodeSelection.Score score:cached)
            if(NodeSelection.allowed(protocol,score.endpoint)) endpoints.add(score.endpoint);
        if(endpoints.isEmpty()) return "";
        StringBuilder text=new StringBuilder();
        if(format==Format.CLASH) text.append("proxies:\n");
        JSONArray singBox=new JSONArray();
        int index=0;
        for(String endpoint:endpoints) {
            String name=String.format(Locale.ROOT,"TrashVPN-%s-%02d",protocol.name(),++index);
            switch(format) {
                case CLASH -> text.append(WarpProfile.proxyYaml(protocol,endpoint,name,identity,settings));
                case V2RAYN -> text.append(wireguardLink(endpoint,name,identity,settings)).append('\n');
                case SING_BOX -> singBox.put(singBoxEndpoint(endpoint,name,identity,settings));
            }
        }
        return format==Format.SING_BOX?new JSONObject().put("endpoints",singBox).toString(2):text.toString();
    }

    private static String wireguardLink(String endpoint,String name,TunnelIdentity identity,NetworkSettings settings) {
        StringBuilder out=new StringBuilder("wireguard://").append(encode(identity.privateKey)).append('@').append(endpoint)
                .append("?publickey=").append(encode(identity.peerKey))
                .append("&address=").append(encode(identity.address+"/32"))
                .append("&mtu=").append(settings.effectiveMtu(0))
                .append("&dns=").append(encode(settings.outboundResolver()));
        if(!identity.clientId.isEmpty()) {
            byte[] bytes=Base64.getDecoder().decode(identity.clientId);
            out.append("&reserved=").append(encode((bytes[0]&255)+","+(bytes[1]&255)+","+(bytes[2]&255)));
        }
        return out.append('#').append(encode(name)).toString();
    }

    private static JSONObject singBoxEndpoint(String endpoint,String name,TunnelIdentity identity,NetworkSettings settings)
            throws JSONException {
        int colon=endpoint.indexOf(':');
        JSONObject peer=new JSONObject().put("address",endpoint.substring(0,colon))
                .put("port",Integer.parseInt(endpoint.substring(colon+1))).put("public_key",identity.peerKey)
                .put("allowed_ips",new JSONArray().put("0.0.0.0/0")).put("persistent_keepalive_interval",25);
        if(!identity.clientId.isEmpty()) {
            JSONArray bytes=new JSONArray();
            for(byte value:Base64.getDecoder().decode(identity.clientId)) bytes.put(value&255);
            peer.put("reserved",bytes);
        }
        return new JSONObject().put("type","wireguard").put("tag",name).put("mtu",settings.effectiveMtu(0))
                .put("address",new JSONArray().put(identity.address+"/32")).put("private_key",identity.privateKey)
                .put("peers",new JSONArray().put(peer));
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value,"UTF-8").replace("+","%20"); }
        catch(UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
