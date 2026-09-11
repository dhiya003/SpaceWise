package com.verve.spacesweep;
import java.util.Set;
public final class FolderExclusions {
    public static String normalize(String path) {
        if(path==null)return "";
        while(path.startsWith("/"))path=path.substring(1);
        while(path.endsWith("/"))path=path.substring(0,path.length()-1);
        return path;
    }
    public static String documentPath(String id) {
        int colon=id.indexOf(':');
        return normalize(colon>=0?id.substring(colon+1):id);
    }
    public static String displayPath(String path) {
        return path.startsWith("Documents / ")?documentPath(path.substring(12)):normalize(path);
    }
    public static boolean matches(String path,Set<String> rules) {
        String candidate=normalize(path);
        for(String rule:rules) {
            String r=normalize(rule);
            if(!r.isEmpty()&&(candidate.equals(r)||candidate.startsWith(r+"/")))return true;
        }
        return false;
    }
}
