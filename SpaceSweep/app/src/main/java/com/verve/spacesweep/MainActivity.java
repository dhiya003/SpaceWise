package com.verve.spacesweep;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import com.verve.spacesweep.DuplicateEngine.*;

/** Local-only Android 11+ cleaner. No network, analytics, account, or broad file-access permission. */
public class MainActivity extends Activity {
    private final int BG=Color.rgb(245,247,244), INK=Color.rgb(21,43,38), MUTED=Color.rgb(85,105,97), GREEN=Color.rgb(0,121,107);
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor thumbnails=new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(120),new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile int viewGeneration=0;
    private CancellationSignal thumbnailCancellation=new CancellationSignal();
    private volatile CancellationSignal scanCancellation=new CancellationSignal();
    private volatile boolean cancelled=false, dead=false;
    private boolean busy=false, deleting=false, needsRefresh=true;
    private boolean returnedFromSettings=false;
    private String lastPermissions="";
    private List<Item> files=new ArrayList<>();
    private List<Group> groups=new ArrayList<>();
    private final Set<String> selected=new LinkedHashSet<>();
    private List<Item> pending=new ArrayList<>();
    private LinearLayout root, body, footer;
    private TextView status;
    private Button deleteButton,reviewButton;
    private String tab="Clean", filter="All", folderFilter=null, message="Your files stay on your phone. Start with a scan.";
    private int page=0, scanFailures=0;
    private final Map<Group,Integer> groupPages=new IdentityHashMap<>();
    private long scannedBytes=0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(getPreferences(MODE_PRIVATE).getBoolean("cleanup_active",false)) {
            message="Previous cleanup was interrupted. No remaining deletions will be resumed. Scan to refresh files.";
            getPreferences(MODE_PRIVATE).edit().putBoolean("cleanup_active",false).apply();
        }
        lastPermissions=permissionState();
        render();
        // Do not mask an interrupted cleanup or repeat deletion after activity recreation.
        if(hasAnyAccess()&&!message.startsWith("Previous cleanup")) scan();
    }
    private String permissionState() {
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE)+":"+granted("android.permission.READ_MEDIA_IMAGES")+":"+
            granted("android.permission.READ_MEDIA_VIDEO")+":"+granted("android.permission.READ_MEDIA_AUDIO")+":"+
            granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")+":"+granted(Manifest.permission.ACCESS_MEDIA_LOCATION);
    }
    @Override protected void onResume() {
        super.onResume();
        String current=permissionState();
        if(!current.equals(lastPermissions)) {
            lastPermissions=current; needsRefresh=true;selected.clear();
            if(busy){cancelled=true;scanCancellation.cancel();}
            if(!deleting){files.clear();groups.clear();scannedBytes=0;message="Permissions changed. Scan again to refresh accessible files.";render();}
        }
        if(returnedFromSettings&&!deleting){returnedFromSettings=false;needsRefresh=true;selected.clear();message="Returned from storage settings. Scan again before deleting files.";render();}
    }
    private boolean granted(String p) { return checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED; }
    private boolean hasAnyAccess() {
        return (Build.VERSION.SDK_INT>=33 ? granted(Manifest.permission.READ_MEDIA_IMAGES)||granted(Manifest.permission.READ_MEDIA_VIDEO)||granted(Manifest.permission.READ_MEDIA_AUDIO)||granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") : granted(Manifest.permission.READ_EXTERNAL_STORAGE))
            || getContentResolver().getPersistedUriPermissions().stream().anyMatch(p->p.isReadPermission()&&isLocalTree(p.getUri()));
    }
    private void permissions() {
        if(busy||deleting)return;
        List<String> p=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=33) {
            p.add(Manifest.permission.READ_MEDIA_IMAGES); p.add(Manifest.permission.READ_MEDIA_VIDEO); p.add(Manifest.permission.READ_MEDIA_AUDIO);
            if(Build.VERSION.SDK_INT>=34) p.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
        } else p.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        p.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        requestPermissions(p.toArray(new String[0]),10);
    }
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] result) {
        super.onRequestPermissionsResult(request,p,result);
        lastPermissions=permissionState();needsRefresh=true;if(request==10)scan();
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String s,int size,int color) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(5),0,dp(5)); return t;
    }
    private TextView title(String s,int size) { TextView t=text(s,size,INK); t.setTypeface(null,Typeface.BOLD); return t; }
    private Button button(String label,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(GREEN);
        b.setOnClickListener(v->{if(!deleting)action.run();}); return b;
    }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void stretch(LinearLayout r,View v) { r.addView(v,new LinearLayout.LayoutParams(0,-2,1)); }
    private LinearLayout card() {
        LinearLayout l=column(); l.setPadding(dp(16),dp(12),dp(16),dp(12)); l.setBackground(background(Color.WHITE,18));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(6),0,dp(6)); body.addView(l,lp); return l;
    }
    private void navigate(String next) { tab=next; selected.clear(); folderFilter=null;filter="All";page=0; render(); }
    private void render() {
        if(dead)return;
        viewGeneration++;thumbnailCancellation.cancel();thumbnailCancellation=new CancellationSignal();thumbnails.getQueue().clear();
        root=column(); root.setBackgroundColor(BG); root.setPadding(dp(18),dp(8),dp(18),0);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(18)+bars.left,dp(8)+bars.top,dp(18)+bars.right,bars.bottom); return insets;
        });
        LinearLayout heading=row(); stretch(heading,title("SpaceSweep",25));
        heading.addView(button(busy?"Stop":"Scan",()->{ if(busy){cancelled=true;scanCancellation.cancel();message="Stopping scan…";status.setText(message);}else if(hasAnyAccess())scan();else permissions(); })); root.addView(heading);
        status=text(busy?"Scanning on device…":message,12,MUTED); root.addView(status);
        ScrollView scroll=new ScrollView(this); body=column(); body.setPadding(0,0,0,dp(16)); scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(!hasAnyAccess()) welcome();
        else if(tab.equals("Clean")) clean();
        else if(tab.equals("Duplicates")) duplicateList();
        else if(tab.equals("Large")) largeList();
        else folders();
        footer=column(); deleteButton=button("",this::confirmDelete); deleteButton.setTextColor(Color.WHITE); deleteButton.setBackground(background(GREEN,16));
        footer.addView(deleteButton,new LinearLayout.LayoutParams(-1,dp(54))); updateSelection();
        reviewButton=button("Review selected files",()->{tab="Large";filter="Selected";folderFilter=null;page=0;render();});footer.addView(reviewButton);updateSelection();
        LinearLayout nav=row(); for(String name:Arrays.asList("Clean","Duplicates","Large","Folders")) {
            Button b=button(name,()->navigate(name)); b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(2),dp(4),dp(2),dp(4)); if(tab.equals(name))b.setTypeface(null,Typeface.BOLD); stretch(nav,b);
        } footer.addView(nav); root.addView(footer); setContentView(root); root.requestApplyInsets();
    }
    private void welcome() {
        body.addView(title("Less clutter.\nMore room.",36));
        body.addView(text("Find exact copies. Keep one. Clear space without digging through charts.",18,MUTED));
        LinearLayout c=card(); c.addView(title("Start with photos & videos",20));
        c.addView(text("Media access lets us scan your library. Original media access is used only to compare complete file bytes, including metadata; no location is displayed or sent anywhere.",14,MUTED));
        c.addView(button("Allow media access",this::permissions));
        body.addView(button("Choose a document folder instead",this::pickFolder));
        body.addView(text("Nothing is deleted during a scan. Exact matches only—not lookalikes. No internet permission, uploads, or subscription.",14,MUTED));
    }
    private String bytes(long n) {
        if(n<0)return "Size unknown";
        if(n>=1073741824L)return String.format(Locale.getDefault(),"%.2f GiB",n/1073741824.0);
        if(n>=1048576)return String.format(Locale.getDefault(),"%.1f MiB",n/1048576.0);
        if(n>=1024)return String.format(Locale.getDefault(),"%.1f KiB",n/1024.0);
        return n+" B";
    }
    private void clean() {
        long savings=groups.stream().mapToLong(Group::savings).sum();
        LinearLayout hero=card(); hero.setBackground(background(Color.rgb(220,240,230),20));
        hero.addView(text("EXACT DUPLICATE COPIES",12,GREEN)); hero.addView(title(bytes(savings),44));
        int count=groups.stream().mapToInt(g->g.removable().size()).sum();
        hero.addView(text(count+" removable copies · one retained per group",15,INK));
        hero.addView(button("Review & select duplicate copies",()->{navigate("Duplicates");selectDuplicates();}));
        LinearLayout large=card(); large.addView(title("Big files, quick wins",22));
        long big=files.stream().filter(i->i.size>=100*1048576L).mapToLong(i->i.size).sum();
        large.addView(text(bytes(big)+" in files at least 100 MiB. Largest first; nothing preselected.",14,MUTED));
        large.addView(button("Review largest files",()->navigate("Large")));
        LinearLayout screenshots=card(); screenshots.addView(title("Screenshots to review",20));
        screenshots.addView(text("Screenshots may be important. Preview and choose what you no longer need.",14,MUTED));
        screenshots.addView(button("Review screenshots",()->{navigate("Large");filter="Screenshots";render();}));
        StatFs disk=new StatFs(Environment.getDataDirectory().getAbsolutePath());
        body.addView(text(bytes(disk.getAvailableBytes())+" available of "+bytes(disk.getTotalBytes())+" internal storage",14,INK));
        body.addView(text("Scan coverage: "+files.size()+" accessible files · "+bytes(scannedBytes)+". Private app data, system files, cloud-only files and ungranted folders are not inspected.",12,MUTED));
        if(Build.VERSION.SDK_INT>=34 && (!granted(Manifest.permission.READ_MEDIA_IMAGES)||!granted(Manifest.permission.READ_MEDIA_VIDEO)))
            body.addView(text("Photo/video access may be limited to selected items. Allow full access for broader coverage.",13,MUTED));
        if(!granted(Manifest.permission.ACCESS_MEDIA_LOCATION))
            body.addView(text("Photo/video exact matching is paused without original-media access. Large-file review still works. Grant access to compare unredacted originals safely.",13,MUTED));
        body.addView(button("Manage media permissions",this::permissions));
        body.addView(button("Add document folder",this::pickFolder));
        body.addView(button("App storage & cache → Android settings",()->openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)));
        body.addView(text("Android controls private app storage. Clear cache or uninstall apps there; SpaceSweep never promises to clear every app's cache.",12,MUTED));
    }
    private void selectDuplicates() {
        if(busy||deleting||needsRefresh)return;
        selected.clear(); int count=0;
        for(Group g:groups)for(Item i:g.removable())if(count++<100)selected.add(i.id);
        render();
    }
    private void duplicateList() {
        body.addView(title("Keep one. Remove copies.",27));
        body.addView(text("SHA-256 plus full byte comparison. Favorites stay protected. The retained copy is a recommendation—not proof of which file was created first.",13,MUTED));
        body.addView(button("Select up to 100 extra copies",this::selectDuplicates));
        if(groups.isEmpty())body.addView(text(busy?"Results appear after the scan finishes.":"No verified exact duplicates in the files scanned.",17,MUTED));
        for(Group g:groups.subList(Math.min(page*5,groups.size()),Math.min(page*5+5,groups.size()))) {
            LinearLayout c=card(); c.addView(title(bytes(g.savings())+" can be removed",20));
            int offset=groupPages.getOrDefault(g,0);
            for(Item i:g.items.subList(Math.min(offset,g.items.size()),Math.min(offset+10,g.items.size()))) {
                addFileRow(c,i,true,g);
            }
            c.addView(text("Copies "+(offset+1)+"–"+Math.min(offset+10,g.items.size())+" of "+g.items.size()+" · Retained: "+g.keeper.name,12,MUTED));
            LinearLayout controls=row();
            if(offset>0)stretch(controls,button("Previous copies",()->{groupPages.put(g,Math.max(0,offset-10));render();}));
            if(offset+10<g.items.size())stretch(controls,button("Next copies",()->{groupPages.put(g,offset+10);render();}));
            c.addView(controls);
        }
        pagination(groups.size(),5);
    }
    private boolean protectedItem(Item i) {
        if(i.favorite || !i.deletable)return true;
        for(Group g:groups)if(g.keeper.id.equals(i.id))return true;
        return false;
    }
    private void addFileRow(LinearLayout c,Item item,boolean duplicate,Group group) {
        LinearLayout r=row();
        ImageView thumb=new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setBackground(background(BG,8));
        thumb.setContentDescription("Preview "+item.name); r.addView(thumb,new LinearLayout.LayoutParams(dp(54),dp(54)));
        if(item.media&&(item.mime.startsWith("image/")||item.mime.startsWith("video/"))&&!thumbnails.isShutdown()) {
            final int generation=viewGeneration;final CancellationSignal signal=thumbnailCancellation;
            thumbnails.execute(()->{try{
                if(dead||generation!=viewGeneration)return;
                android.graphics.Bitmap b=getContentResolver().loadThumbnail(Uri.parse(item.id),new Size(dp(70),dp(70)),signal);
                thumb.post(()->{if(!dead&&generation==viewGeneration)thumb.setImageBitmap(b);else b.recycle();});
            }catch(Exception ignored){}});
        } else thumb.setImageResource(android.R.drawable.ic_menu_save);
        thumb.setOnClickListener(v->preview(item));
        LinearLayout labels=column(); labels.setPadding(dp(10),0,dp(4),0); labels.addView(title(item.name,14));
        labels.addView(text(bytes(item.size)+(item.favorite?" · Favorite":"")+(duplicate&&group.keeper==item?" · KEEP":""),12,GREEN));
        TextView path=text(item.path,11,MUTED); path.setMaxLines(2); labels.addView(path); labels.setOnClickListener(v->preview(item)); stretch(r,labels);
        CheckBox check=new CheckBox(this); check.setContentDescription("Select "+item.name+" for deletion");
        check.setChecked(selected.contains(item.id)); check.setEnabled(!busy&&!deleting&&!needsRefresh&&!protectedItem(item));
        check.setOnCheckedChangeListener((v,on)->{
            if(busy||deleting||needsRefresh)return;
            if(on&&selected.size()>=100){check.setChecked(false);toast("Up to 100 files per batch.");return;}
            if(on)selected.add(item.id);else selected.remove(item.id);updateSelection();
        }); r.addView(check); c.addView(r);
        if(duplicate&&group.keeper!=item) c.addView(button("Keep this copy instead",()->{
            if(busy||needsRefresh)return;for(Item member:group.items)selected.remove(member.id);group.keeper=item;render();
        }));
        else if(!duplicate&&protectedItem(item)) c.addView(text(item.favorite?"Favorite protected":"Retained copy / read-only item protected",11,MUTED));
    }
    private boolean matches(Item i) {
        if(folderFilter!=null&&!i.path.equals(folderFilter))return false;
        switch(filter) {
            case "Selected":return selected.contains(i.id);
            case "Videos":return i.mime.startsWith("video/");
            case "Photos":return i.mime.startsWith("image/");
            case "Audio":return i.mime.startsWith("audio/");
            case "Screenshots":return i.mime.startsWith("image/")&&(i.path+"/"+i.name).toLowerCase(Locale.ROOT).contains("screenshot");
            case "Documents":return !i.media;
            default:return true;
        }
    }
    private void largeList() {
        body.addView(title("Largest first",29));
        body.addView(text(folderFilter==null?"Preview a file, select it, then delete. No auto-selection based on size or age.":folderFilter,13,MUTED));
        HorizontalScrollView chips=new HorizontalScrollView(this); LinearLayout choices=row();
        for(String kind:Arrays.asList("All","Videos","Photos","Audio","Screenshots","Documents"))choices.addView(button((filter.equals(kind)?"• ":"")+kind,()->{filter=kind;selected.clear();page=0;render();}));
        chips.addView(choices);body.addView(chips);
        List<Item> list=new ArrayList<>(); for(Item i:files)if(matches(i))list.add(i);
        list.sort(Comparator.comparingLong((Item i)->i.size).reversed());
        body.addView(text(list.size()+" files · "+bytes(list.stream().mapToLong(i->Math.max(0,i.size)).sum())+" known size",13,MUTED));
        for(int n=Math.min(page*30,list.size());n<Math.min(page*30+30,list.size());n++)addFileRow(card(),list.get(n),false,null);
        pagination(list.size(),30);
    }
    private void pagination(int count,int pageSize) {
        if(count<=pageSize)return;
        LinearLayout controls=row();
        if(page>0)stretch(controls,button("Previous",()->{page--;render();}));
        stretch(controls,text("Page "+(page+1)+" / "+((count+pageSize-1)/pageSize),12,MUTED));
        if((page+1)*pageSize<count)stretch(controls,button("Next",()->{page++;render();}));
        body.addView(controls);
    }
    private void folders() {
        body.addView(title("Find the space hogs",27));
        body.addView(text("Accessible folders, largest first. Tap a folder to review files—not a chart.",14,MUTED));
        Map<String,Long> sums=new HashMap<>();for(Item i:files)sums.merge(i.path,Math.max(0,i.size),Long::sum);
        List<String> paths=new ArrayList<>(sums.keySet());paths.sort(Comparator.comparingLong((String p)->sums.get(p)).reversed());
        for(String path:paths.subList(Math.min(page*30,paths.size()),Math.min(page*30+30,paths.size()))) {
            LinearLayout c=card();c.addView(title(bytes(sums.get(path)),22));c.addView(text(path,14,MUTED));
            c.addView(button("Review files",()->{navigate("Large");folderFilter=path;filter="All";render();}));
        }
        pagination(paths.size(),30);
        body.addView(button("Choose a document folder",this::pickFolder));
        body.addView(button("Downloads / restricted folders → Files",()->{
            try{startActivity(new Intent("android.intent.action.VIEW_DOWNLOADS"));returnedFromSettings=true;}catch(Exception e){openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);}
        }));
        body.addView(text("Folder grants scan non-media documents only, avoiding overlap with the media library. Document duplicate matching is not included in this version. Android may prevent selecting Downloads, storage root, Android/data and Android/obb.",12,MUTED));
    }
    private void updateSelection() {
        if(deleteButton==null)return;
        long amount=files.stream().filter(i->selected.contains(i.id)).mapToLong(i->i.size).sum();
        deleteButton.setText(needsRefresh?"Scan to refresh before deleting":selected.isEmpty()?"Select files to free space":"Delete "+selected.size()+" files · "+bytes(amount));
        deleteButton.setEnabled(!selected.isEmpty()&&!busy&&!deleting&&!needsRefresh); deleteButton.setAlpha(deleteButton.isEnabled()?1f:0.45f);
        if(reviewButton!=null){reviewButton.setEnabled(!selected.isEmpty()&&!deleting);reviewButton.setVisibility(selected.isEmpty()?View.GONE:View.VISIBLE);}
    }
    private void preview(Item i) {
        if(deleting||busy)return;
        try {startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(i.id),i.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
        catch(Exception e){toast("No installed app can preview this file.");}
    }
    private void openSettings(String action) {try{startActivity(new Intent(action));returnedFromSettings=true;}catch(Exception e){toast("Open Android Settings → Storage.");}}
    private void toast(String s) {Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void pickFolder() {
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(Intent.EXTRA_LOCAL_ONLY,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent,20);
    }
    private void scan() {
        if(busy||deleting||dead)return;busy=true;cancelled=false;needsRefresh=true;scanCancellation=new CancellationSignal();selected.clear();render();
        worker.execute(()->{
            List<Item> found=new ArrayList<>(); scanFailures=0;
            try {
                queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,"image/",found);
                queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,"video/",found);
                queryMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,"audio/",found);
                scanTrees(found);
                postStatus("Found "+found.size()+" files. Comparing possible copies…");
                ScanResult result=DuplicateEngine.scan(found,()->cancelled||dead,this::postStatus);
                int failures=scanFailures+result.unreadable;
                runOnUiThread(()->{
                    if(dead)return;busy=false;
                    if(cancelled){message="Scan cancelled. Scan again before deleting.";render();return;}
                    needsRefresh=false;files=found;groups=result.groups;groupPages.clear();page=0;scannedBytes=files.stream().mapToLong(i->Math.max(0,i.size)).sum();
                    message="Scan complete · "+files.size()+" accessible files"+(failures>0?" · "+failures+" access/read checks skipped":"");render();
                });
            }catch(Exception e){runOnUiThread(()->{if(dead)return;busy=false;needsRefresh=true;message=cancelled?"Scan stopped. Scan again before deleting.":"Scan incomplete. Check permissions and retry.";render();});}
        });
    }
    private long lastProgress=0;
    private void postStatus(String s) {
        long now=SystemClock.elapsedRealtime();if(now-lastProgress<150)return;lastProgress=now;
        runOnUiThread(()->{if(!dead&&busy&&!cancelled&&status!=null)status.setText(s);});
    }
    private void queryMedia(Uri collection,String fallback,List<Item> out) throws IOException {
        boolean visual=!fallback.equals("audio/");
        String permission=Build.VERSION.SDK_INT>=33?(fallback.equals("image/")?Manifest.permission.READ_MEDIA_IMAGES:fallback.equals("video/")?Manifest.permission.READ_MEDIA_VIDEO:Manifest.permission.READ_MEDIA_AUDIO):Manifest.permission.READ_EXTERNAL_STORAGE;
        if(!granted(permission)&&!(visual&&Build.VERSION.SDK_INT>=34&&granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")))return;
        String[] projection={"_id","_display_name","_size","date_modified","relative_path","mime_type","is_favorite"};
        try(Cursor c=getContentResolver().query(collection,projection,"is_pending = 0 AND is_trashed = 0",null,null,scanCancellation)) {
            if(c==null){scanFailures++;return;}
            while(c.moveToNext()) {
                DuplicateEngine.check(()->cancelled||dead);
                Uri uri=ContentUris.withAppendedId(collection,c.getLong(0)); long size=c.getLong(2);if(size<=0)continue;
                boolean originalAccess=!visual||granted(Manifest.permission.ACCESS_MEDIA_LOCATION);
                Source source=()->{
                    try{return getContentResolver().openInputStream(visual?MediaStore.setRequireOriginal(uri):uri);}
                    catch(RuntimeException e){throw new IOException("Original bytes unavailable",e);}
                };
                out.add(new Item(uri.toString(),c.getString(1)==null?"Untitled":c.getString(1),c.getString(4)==null?"Media":c.getString(4),c.getString(5)==null?fallback+"*":c.getString(5),size,c.getLong(3)*1000,c.getInt(6)==1,true,originalAccess,true,source));
            }
        }catch(SecurityException|IllegalArgumentException e){scanFailures++;}
    }
    private void scanTrees(List<Item> out) throws IOException {
        Set<String> visited=new HashSet<>();
        for(UriPermission permission:getContentResolver().getPersistedUriPermissions()) {
            if(!permission.isReadPermission())continue;
            Uri tree=permission.getUri();if(!isLocalTree(tree))continue;
            ArrayDeque<String[]> queue=new ArrayDeque<>();String rootId=DocumentsContract.getTreeDocumentId(tree);queue.add(new String[]{rootId,"Documents / "+rootId});
            while(!queue.isEmpty()) {
                DuplicateEngine.check(()->cancelled||dead);
                String[] next=queue.remove();String identity=tree.getAuthority()+":"+next[0];if(!visited.add(identity))continue;
                if(visited.size()>100000)throw new IOException("Too many folders. Choose smaller folders.");
                Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,next[0]);
                try(Cursor c=getContentResolver().query(children,new String[]{"document_id","_display_name","mime_type","_size","last_modified","flags"},null,null,null,scanCancellation)) {
                    if(c==null){scanFailures++;continue;}
                    while(c.moveToNext()) {
                        DuplicateEngine.check(()->cancelled||dead);
                        String id=c.getString(0),name=c.getString(1),mime=c.getString(2);
                        if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){queue.add(new String[]{id,next[1]+"/"+name});continue;}
                        if(mime!=null&&(mime.startsWith("image/")||mime.startsWith("video/")||mime.startsWith("audio/")))continue;
                        if(!visited.add(tree.getAuthority()+":"+id))continue;
                        Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,id);boolean canDelete=permission.isWritePermission()&&(c.getInt(5)&DocumentsContract.Document.FLAG_SUPPORTS_DELETE)!=0;
                        // Unknown sizes remain unknown in the UI and never inflate savings.
                        long size=c.isNull(3)?-1:c.getLong(3);
                        out.add(new Item(uri.toString(),name==null?"Untitled":name,next[1],mime==null?"application/octet-stream":mime,size,c.getLong(4),false,false,false,canDelete&&size>=0,()->getContentResolver().openInputStream(uri)));
                    }
                }catch(SecurityException|IllegalArgumentException e){scanFailures++;}
            }
        }
    }
    private boolean isLocalTree(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority())&&DocumentsContract.isTreeUri(uri);
    }
    private void confirmDelete() {
        if(selected.isEmpty()||busy||deleting||needsRefresh)return;
        long size=files.stream().filter(i->selected.contains(i.id)).mapToLong(i->i.size).sum();
        new AlertDialog.Builder(this).setTitle("Permanently delete "+selected.size()+" files?")
            .setMessage(bytes(size)+" of selected file data. This does not move files to trash and cannot be undone in SpaceSweep. One verified copy stays in each duplicate group. Android will also ask permission for media files.")
            .setNegativeButton("Keep files",null).setPositiveButton("Delete selected",(d,w)->prepareDeletion()).show();
    }
    private void prepareDeletion() {
        if(deleting||busy||needsRefresh)return;
        final Set<String> ids=new HashSet<>(selected);
        try {
            pending=new ArrayList<>(DuplicateEngine.deletionBatch(files,ids));
            if(pending.stream().anyMatch(this::protectedItem))throw new IOException("A retained copy is selected");
        } catch(IOException e){toast(e.getMessage());return;}
        deleting=true;cancelled=false;render();
        status.setText("Rechecking retained copies before deletion…");
        worker.execute(()->{
            try {
                // Commit before any side effect; activity/process recreation never resumes this batch.
                if(!getPreferences(MODE_PRIVATE).edit().putBoolean("cleanup_active",true).commit())throw new IOException("Cannot save cleanup state");
                for(Item i:pending){DuplicateEngine.check(()->dead);validateCurrentFile(i);}
                DuplicateEngine.validateDeletion(groups,ids,()->dead);
                runOnUiThread(()->{if(!dead)requestMediaDeletion();});
            }catch(Exception e){runOnUiThread(()->{if(dead)return;endTransaction();needsRefresh=true;message="Deletion stopped: "+e.getMessage();render();});}
        });
    }
    private void validateCurrentFile(Item item)throws IOException {
        String[] columns=item.media?new String[]{"_size","date_modified","is_favorite","is_pending","is_trashed"}:
            new String[]{"_size","last_modified","flags"};
        try(Cursor c=getContentResolver().query(Uri.parse(item.id),columns,null,null,null)) {
            if(c==null||!c.moveToFirst())throw new IOException("File is no longer accessible: "+item.name);
            long size=c.isNull(0)?-1:c.getLong(0),modified=c.getLong(1)*(item.media?1000:1);
            boolean favorite=item.media&&c.getInt(2)==1;
            boolean deletable=item.media?(c.getInt(3)==0&&c.getInt(4)==0):((c.getInt(2)&DocumentsContract.Document.FLAG_SUPPORTS_DELETE)!=0);
            DuplicateEngine.validateMetadata(item,size,modified,favorite,deletable);
        }catch(SecurityException|IllegalArgumentException e){throw new IOException("File permission changed. Rescan first.",e);}
    }
    private void endTransaction() {
        deleting=false;pending.clear();selected.clear();
        getPreferences(MODE_PRIVATE).edit().putBoolean("cleanup_active",false).apply();
    }
    private void requestMediaDeletion() {
        List<Uri> uris=new ArrayList<>();for(Item i:pending)if(i.media)uris.add(Uri.parse(i.id));
        if(uris.isEmpty()){finishDeletion(true);return;}
        try {PendingIntent request=MediaStore.createDeleteRequest(getContentResolver(),uris);startIntentSenderForResult(request.getIntentSender(),30,null,0,0,0);}
        catch(Exception e){endTransaction();needsRefresh=true;message="Android could not approve deletion. No document files were deleted.";render();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==20&&result==RESULT_OK&&data!=null&&data.getData()!=null) {
            if(!isLocalTree(data.getData())){toast("Choose a folder under internal storage or SD card. Cloud and other document providers are not supported.");return;}
            try {getContentResolver().takePersistableUriPermission(data.getData(),data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));scan();}
            catch(SecurityException e){toast("Folder access could not be saved.");}
        }
        if(request==30) {
            if(!deleting||pending.isEmpty()) {
                // A result can arrive after rotation/process recreation. Never replay document deletion.
                message="Android cleanup returned after an interruption. Scan to check the result.";needsRefresh=true;render();return;
            }
            if(result==RESULT_OK)finishDeletion(true);
            else {endTransaction();needsRefresh=true;message="Deletion cancelled. Document files were not touched. Refreshing…";scan();}
        }
    }
    private void finishDeletion(boolean approved) {
        List<Item> targets=new ArrayList<>(pending);
        worker.execute(()->{
            int removed=0,unconfirmed=0;long fileBytes=0;
            for(Item i:targets) {
                if(dead)break;
                try {
                    if(!i.media&&approved) {
                        // Media approval can take time. Recheck each document immediately before deletion.
                        validateCurrentFile(i);DuplicateEngine.check(()->dead);
                        if(DocumentsContract.deleteDocument(getContentResolver(),Uri.parse(i.id))){removed++;fileBytes+=i.size;}else unconfirmed++;
                        continue;
                    }
                    try(Cursor c=getContentResolver().query(Uri.parse(i.id),new String[]{i.media?"_id":"document_id"},null,null,null)) {
                        if(c!=null&&c.getCount()==0){removed++;fileBytes+=i.size;}else unconfirmed++;
                    }
                }catch(Exception e){unconfirmed++;}
            }
            final String summary="Confirmed removed: "+removed+" files ("+bytes(fileBytes)+" file bytes)."+(unconfirmed>0?" "+unconfirmed+" could not be confirmed; the next scan will refresh them.":"");
            runOnUiThread(()->{
                if(dead)return;endTransaction();needsRefresh=true;files.clear();groups.clear();scannedBytes=0;message=summary;scan();
                new AlertDialog.Builder(this).setTitle("Cleanup result").setMessage(summary+"\nAvailable storage can differ from file bytes. Results are refreshing automatically.").setPositiveButton("Done",null).show();
            });
        });
    }
    @Override protected void onDestroy() {
        dead=true;cancelled=true;scanCancellation.cancel();thumbnailCancellation.cancel();worker.shutdownNow();thumbnails.shutdownNow();super.onDestroy();
    }
}
