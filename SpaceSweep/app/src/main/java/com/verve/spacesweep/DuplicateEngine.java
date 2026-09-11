package com.verve.spacesweep;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.function.*;

/** No Android dependency: byte-exact duplicate detection and deletion invariants. */
public final class DuplicateEngine {
    public interface Source { InputStream open() throws IOException; }
    public static final class Item {
        public final String id, name, path, mime;
        public final long size, modified;
        public final boolean favorite, media, exactEligible, deletable;
        public final Source source;
        public Item(String id, String name, String path, String mime, long size, long modified,
                    boolean favorite, boolean media, boolean exactEligible, boolean deletable, Source source) {
            this.id=id; this.name=name; this.path=path; this.mime=mime; this.size=size;
            this.modified=modified; this.favorite=favorite; this.media=media;
            this.exactEligible=exactEligible; this.deletable=deletable; this.source=source;
        }
    }
    public static final class Group {
        public final List<Item> items;
        public Item keeper;
        Group(List<Item> items) {
            this.items=new ArrayList<>(items);
            this.items.sort(Comparator.comparing((Item i)->!i.favorite)
                .thenComparing(i->!i.path.toLowerCase(Locale.ROOT).startsWith("dcim/camera/"))
                .thenComparingLong(i->i.modified).thenComparing(i->i.id));
            keeper=this.items.get(0);
        }
        public List<Item> removable() {
            List<Item> result=new ArrayList<>();
            for(Item i:items) if(i!=keeper && !i.favorite && i.deletable) result.add(i);
            return result;
        }
        public long savings() { return removable().stream().mapToLong(i->i.size).sum(); }
    }
    public static final class ScanResult {
        public final List<Group> groups=new ArrayList<>();
        public int unreadable;
    }
    static void check(BooleanSupplier cancel) throws InterruptedIOException {
        if(cancel.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }
    static String digest(Item item, BooleanSupplier cancel) throws IOException {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            long count=0;
            check(cancel);
            InputStream raw=item.source.open();
            if(raw==null) throw new IOException("Cannot open file");
            try(InputStream in=new BufferedInputStream(raw)) {
                    byte[] buffer=new byte[256*1024]; int n;
                    while((n=in.read(buffer))!=-1) {
                        check(cancel); md.update(buffer,0,n); count+=n;
                        if(count>item.size)throw new IOException("File grew while scanning");
                    }
            }
            if(count!=item.size) throw new IOException("File changed while scanning");
            return Base64.getEncoder().encodeToString(md.digest());
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static boolean equalBytes(Item a, Item b, BooleanSupplier cancel) throws IOException {
        check(cancel);
        if(a.size!=b.size) return false;
        InputStream ar=a.source.open();
        if(ar==null) throw new IOException("Cannot open retained copy");
        try(InputStream ai=new BufferedInputStream(ar)) {
            InputStream br=b.source.open();
            if(br==null) throw new IOException("Cannot open duplicate");
            try(InputStream bi=new BufferedInputStream(br)) {
                long count=0; byte[] ab=new byte[65536],bb=new byte[65536];
                while(true) {
                    check(cancel);
                    int an=readBlock(ai,ab,cancel),bn=readBlock(bi,bb,cancel);
                    if(an!=bn) return false;
                    for(int j=0;j<an;j++)if(ab[j]!=bb[j])return false;
                    count+=an;
                    if(count>a.size)return false;
                    if(an==0) return count==a.size;
                }
            }
        }
    }
    private static int readBlock(InputStream in,byte[] buffer,BooleanSupplier cancel) throws IOException {
        int used=0;
        while(used<buffer.length) {
            check(cancel);
            int n=in.read(buffer,used,buffer.length-used);if(n<0)break;
            if(n==0){int value=in.read();if(value<0)break;buffer[used++]=(byte)value;}else used+=n;
        }
        return used;
    }
    public static ScanResult scan(List<Item> items, BooleanSupplier cancel, Consumer<String> progress) throws IOException {
        check(cancel);
        ScanResult result=new ScanResult();
        Map<Long,List<Item>> sizes=new LinkedHashMap<>(); Set<String> seen=new HashSet<>();
        for(Item i:items) {
            check(cancel);
            if(i.size>0 && i.exactEligible && seen.add(i.id)) sizes.computeIfAbsent(i.size,k->new ArrayList<>()).add(i);
        }
        int total=sizes.values().stream().filter(g->g.size()>1).mapToInt(List::size).sum(), done=0;
        for(List<Item> candidates:sizes.values()) {
            if(candidates.size()<2) continue;
            Map<String,List<Item>> hashes=new LinkedHashMap<>();
            for(Item i:candidates) {
                check(cancel); progress.accept("Checking duplicates " + (++done) + "/" + total);
                try { hashes.computeIfAbsent(digest(i,cancel),k->new ArrayList<>()).add(i); }
                catch(InterruptedIOException e) { throw e; }
                catch(IOException | SecurityException e) { result.unreadable++; }
            }
            for(List<Item> sameHash:hashes.values()) {
                if(sameHash.size()<2) continue;
                List<Item> verified=new ArrayList<>(); Item anchor=sameHash.get(0); verified.add(anchor);
                for(int n=1;n<sameHash.size();n++) {
                    try { if(equalBytes(anchor,sameHash.get(n),cancel)) verified.add(sameHash.get(n)); }
                    catch(InterruptedIOException e) { throw e; }
                    catch(IOException | SecurityException e) { result.unreadable++; }
                }
                if(verified.size()>1) result.groups.add(new Group(verified));
            }
        }
        result.groups.sort(Comparator.comparingLong(Group::savings).reversed());
        return result;
    }
    /** Re-read both copies immediately before requesting deletion; never select the survivor. */
    public static void validateDeletion(List<Group> groups, Set<String> selected, BooleanSupplier cancel) throws IOException {
        for(Group g:groups) {
            check(cancel);
            boolean touches=g.items.stream().anyMatch(i->selected.contains(i.id));
            if(!touches) continue;
            if(selected.contains(g.keeper.id)) throw new IOException("Retained copy is protected");
            for(Item i:g.items) if(selected.contains(i.id)) {
                if(i.favorite || !i.deletable) throw new IOException("Favorite or read-only file is protected");
                if(!equalBytes(g.keeper,i,cancel)) throw new IOException("Copies changed. Rescan before deleting.");
            }
        }
    }
    public static List<Item> deletionBatch(List<Item> files,Set<String> selected) throws IOException {
        if(selected.isEmpty()||selected.size()>100)throw new IOException("Select between 1 and 100 files");
        List<Item> batch=new ArrayList<>();Set<String> found=new HashSet<>();
        for(Item i:files)if(selected.contains(i.id)&&found.add(i.id)) {
            if(i.favorite||!i.deletable)throw new IOException("Favorite or read-only file is protected");
            batch.add(i);
        }
        if(found.size()!=selected.size())throw new IOException("Selection is outdated. Rescan first.");
        return Collections.unmodifiableList(batch);
    }
    /** Fail closed if metadata changed or the provider now protects a selected file. */
    public static void validateMetadata(Item scanned,long currentSize,long currentModified,boolean favorite,boolean deletable) throws IOException {
        if(favorite||!deletable)throw new IOException("File is now protected or read-only: "+scanned.name);
        if(currentSize!=scanned.size||currentModified!=scanned.modified)
            throw new IOException("File changed since scanning: "+scanned.name+". Rescan first.");
    }
}
