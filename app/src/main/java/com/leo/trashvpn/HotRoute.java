package com.leo.trashvpn;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.*;

/** One stable TUN route and independent, trace-only loopback entrances into the same outbounds. */
final class HotRoute {
    static String fixedKey(WarpProfile.Protocol p) { return "fixed."+p.name()+".true"; }
    static final class Node {
        final String name;
        final int port;
        Node(String name,int port) { this.name=name; this.port=port; }
    }
    final Map<String,Node> nodes;
    HotRoute(List<String> endpoints,List<Integer> ports) {
        if(endpoints.isEmpty()||endpoints.size()!=ports.size()) throw new IllegalArgumentException();
        Map<String,Node> out=new LinkedHashMap<>();
        for(int i=0;i<endpoints.size();i++) out.put(endpoints.get(i),new Node("warp-"+i,ports.get(i)));
        nodes=Collections.unmodifiableMap(out);
    }
    static List<String> endpoints(WarpProfile.Protocol p,String initial,List<NodeSelection.Score> scores,boolean seeds) {
        Set<String> unique=new LinkedHashSet<>();
        if(NodeSelection.allowed(p,initial)) unique.add(initial);
        if(seeds) unique.addAll(p.endpoints);
        for(NodeSelection.Score s:scores) if(NodeSelection.allowed(p,s.endpoint)) unique.add(s.endpoint);
        return new ArrayList<>(unique);
    }
    static List<String> visibleEndpoints(WarpProfile.Protocol p,String pinned,List<NodeSelection.Score> scores) {
        List<String> visible=endpoints(p,null,scores,true);
        if(NodeSelection.allowed(p,pinned) && !visible.contains(pinned)) visible.add(pinned);
        return visible;
    }
    static List<String> sessionEndpoints(WarpProfile.Protocol p,String initial,String pinned,List<NodeSelection.Score> scores) {
        Set<String> order=new LinkedHashSet<>();
        if(NodeSelection.allowed(p,initial)) order.add(initial);
        order.addAll(visibleEndpoints(p,pinned,scores));
        return new ArrayList<>(order);
    }
    static HotRoute allocate(List<String> endpoints) throws IOException {
        List<ServerSocket> held=new ArrayList<>();
        List<Integer> ports=new ArrayList<>();
        InetAddress loopback=InetAddress.getByName("127.0.0.1");
        try {
            for(String ignored:endpoints) {
                ServerSocket s=new ServerSocket(0,1,loopback);
                held.add(s);
                ports.add(s.getLocalPort());
            }
        } finally {
            for(ServerSocket socket:held) socket.close();
        }
        return new HotRoute(endpoints,ports);
    }
    String profile(WarpProfile.Protocol p,TunnelIdentity identity,NetworkSettings settings,boolean routeTun) {
        String initial=nodes.keySet().iterator().next();
        String base=WarpProfile.render(p,initial,identity,settings);
        StringBuilder y=new StringBuilder(base.substring(0,base.indexOf("rules:\n")));
        y.append("rules:\n  - MATCH,").append(routeTun?WarpProfile.PROXY:"REJECT")
                .append("\nproxy-groups:\n  - name: ").append(WarpProfile.PROXY).append("\n    type: select\n    proxies: [");
        StringJoiner names=new StringJoiner(", ");
        for(Node node:nodes.values()) names.add(node.name);
        y.append(names).append("]\nlisteners:\n");
        for(Node n:nodes.values())
            y.append("  - name: trace-").append(n.name).append("\n    type: tunnel\n    listen: 127.0.0.1\n    port: ")
                    .append(n.port).append("\n    network: [tcp]\n    target: www.cloudflare.com:443\n    proxy: ")
                    .append(n.name).append('\n');
        y.append("proxies:\n");
        for(Map.Entry<String,Node> entry:nodes.entrySet())
            y.append(WarpProfile.proxyYaml(p,entry.getKey(),entry.getValue().name,identity,settings));
        return y.toString();
    }
}
