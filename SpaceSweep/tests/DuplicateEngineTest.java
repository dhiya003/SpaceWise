package com.verve.spacesweep;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.verve.spacesweep.DuplicateEngine.*;

public class DuplicateEngineTest {
    static int passed=0;
    static Item item(String id,String path,boolean favorite,byte[] bytes) {
        return new Item(id,id,path,"image/jpeg",bytes.length,100,favorite,true,true,true,()->new ByteArrayInputStream(bytes));
    }
    static ScanResult scan(Item... items)throws IOException{return DuplicateEngine.scan(Arrays.asList(items),()->false,s->{});}
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);passed++;}
    static void rejects(RunnableIO action,String label)throws Exception{try{action.run();throw new AssertionError(label);}catch(IOException expected){check(true,label);}}
    interface RunnableIO{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        byte[] data={1,2,3,4};
        Item camera=item("camera","DCIM/Camera/",false,data),copy=item("copy","Download/",false,data);
        ScanResult two=scan(copy,camera);
        check(two.groups.size()==1,"find identical photos");
        check(two.groups.get(0).keeper==camera,"prefer camera copy");
        check(two.groups.get(0).savings()==4,"count extra copy only");
        check(scan(camera,item("different","Download/",false,new byte[]{4,3,2,1})).groups.isEmpty(),"same size is not a duplicate");
        check(scan(camera,camera).groups.isEmpty(),"do not duplicate one URI");
        check(scan(item("empty1","",false,new byte[0]),item("empty2","",false,new byte[0])).groups.isEmpty(),"ignore empty files");
        Item favorite=item("fav","Pictures/",true,data);
        Group fav=scan(copy,camera,favorite).groups.get(0);
        check(fav.keeper==favorite,"favorite wins keeper priority");
        fav.keeper=camera;
        check(fav.removable().size()==1&&fav.removable().get(0)==copy,"favorites remain protected after keeper swap");
        DuplicateEngine.validateDeletion(two.groups,new HashSet<>(Arrays.asList("copy")),()->false);
        check(true,"valid duplicate deletion passes");
        rejects(()->DuplicateEngine.validateDeletion(two.groups,new HashSet<>(Arrays.asList("camera","copy")),()->false),"cannot delete all copies");
        AtomicReference<byte[]> changing=new AtomicReference<>(data);
        Item mutable=new Item("mutable","mutable","","video/mp4",4,0,false,true,true,true,()->new ByteArrayInputStream(changing.get()));
        ScanResult before=scan(camera,mutable);changing.set(new byte[]{9,8,7,6});
        rejects(()->DuplicateEngine.validateDeletion(before.groups,new HashSet<>(Arrays.asList("mutable")),()->false),"changed duplicate blocks deletion");
        Item broken=new Item("broken","broken","","image/jpeg",4,0,false,true,true,true,()->{throw new IOException("Denied");});
        ScanResult denied=scan(camera,broken);
        check(denied.groups.isEmpty()&&denied.unreadable==1,"unreadable files never classified as duplicate");
        Item wrongSize=new Item("wrong","wrong","","image/jpeg",4,0,false,true,true,true,()->new ByteArrayInputStream(new byte[]{1,2}));
        check(scan(camera,wrongSize).groups.isEmpty(),"changing file size rejected");
        Item ineligible=new Item("redacted","redacted","","image/jpeg",4,0,false,true,false,true,()->new ByteArrayInputStream(data));
        check(scan(camera,ineligible).groups.isEmpty(),"redacted or untrusted sources excluded");
        rejects(()->DuplicateEngine.scan(Arrays.asList(camera,copy),()->true,s->{}),"scan cancellation");
        byte[] large=new byte[500123];new Random(12).nextBytes(large);
        check(scan(item("v1","",false,large),item("v2","",false,large)).groups.size()==1,"multi-block media match");
        AtomicReference<byte[]> keeperData=new AtomicReference<>(data);
        Item retained=new Item("retained","retained","DCIM/Camera/","image/jpeg",4,0,false,true,true,true,()->{
            if(keeperData.get()==null)throw new FileNotFoundException();return new ByteArrayInputStream(keeperData.get());});
        ScanResult survivor=scan(retained,copy);keeperData.set(null);
        rejects(()->DuplicateEngine.validateDeletion(survivor.groups,new HashSet<>(Arrays.asList("copy")),()->false),"missing keeper blocks deletion");
        rejects(()->DuplicateEngine.validateMetadata(copy,4,100,true,true),"newly favorited copy blocks deletion");
        rejects(()->DuplicateEngine.validateMetadata(copy,4,101,false,true),"same-size metadata change blocks deletion");
        rejects(()->DuplicateEngine.validateMetadata(copy,5,100,false,true),"grown file blocks deletion");
        rejects(()->DuplicateEngine.validateMetadata(copy,4,100,false,false),"lost write access blocks deletion");
        DuplicateEngine.validateMetadata(copy,4,100,false,true);check(true,"unchanged metadata accepted");
        rejects(()->DuplicateEngine.deletionBatch(Arrays.asList(copy),Collections.emptySet()),"empty deletion rejected");
        rejects(()->DuplicateEngine.deletionBatch(Arrays.asList(copy),new HashSet<>(Arrays.asList("missing"))),"stale selection rejected");
        Set<String> excessive=new HashSet<>();for(int n=0;n<101;n++)excessive.add("file"+n);
        rejects(()->DuplicateEngine.deletionBatch(Collections.emptyList(),excessive),"batch limit enforced independently of UI");
        Item readonly=new Item("readonly","readonly","","image/jpeg",4,0,false,true,true,false,()->new ByteArrayInputStream(data));
        rejects(()->DuplicateEngine.deletionBatch(Arrays.asList(readonly),new HashSet<>(Arrays.asList("readonly"))),"read-only batch rejected");
        rejects(()->DuplicateEngine.deletionBatch(Arrays.asList(favorite),new HashSet<>(Arrays.asList("fav"))),"favorite batch rejected");
        check(DuplicateEngine.deletionBatch(Arrays.asList(copy,copy),new HashSet<>(Arrays.asList("copy"))).size()==1,"deletion targets deduplicated");
        java.util.concurrent.atomic.AtomicInteger closeCount=new java.util.concurrent.atomic.AtomicInteger();
        Item singleClose=new Item("oneclose","oneclose","","image/jpeg",4,0,false,true,true,true,()->new ByteArrayInputStream(data){
            @Override public void close()throws IOException{if(closeCount.incrementAndGet()>1)throw new IOException("Double close");}
        });
        DuplicateEngine.digest(singleClose,()->false);check(closeCount.get()==1,"hash stream closed exactly once");
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        Item slow=new Item("slow","slow","","video/mp4",500123,0,false,true,true,true,()->new ByteArrayInputStream(large){
            @Override public synchronized int read(byte[] b,int off,int len){reads.incrementAndGet();return super.read(b,off,Math.min(1,len));}
        });
        rejects(()->DuplicateEngine.equalBytes(slow,item("normal","",false,large),()->reads.get()>5),"cancel inside short-read block");
        check(scan(item("z-camera","DCIM/Camera/",false,data),item("a-backup","DCIM/CameraBackup/",false,data)).groups.get(0).keeper.id.equals("z-camera"),"camera backup folder not mistaken for original folder");
        rejects(()->DuplicateEngine.scan(Collections.emptyList(),()->true,s->{}),"empty cancelled scan rejected");
        System.out.println("All "+passed+" tests passed.");
    }
}
