package com.spacewise.cleaner;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(23,21,19);
    private static final int CARD = Color.rgb(42,37,34);
    private static final int TEXT = Color.rgb(244,239,232);
    private static final int MUTED = Color.rgb(169,159,150);
    private static final int CORAL = Color.rgb(255,107,74);
    private static final int GREEN = Color.rgb(128,199,160);

    private LinearLayout page;
    private ProgressBar progress;
    private TextView status;
    private final List<StorageItem> photos = new ArrayList<>();
    private final List<StorageItem> videos = new ArrayList<>();
    private final List<StorageItem> audio = new ArrayList<>();
    private final List<StorageItem> documents = new ArrayList<>();
    private final List<StorageItem> apps = new ArrayList<>();
    private final List<DuplicateGroup> duplicateGroups = new ArrayList<>();
    private final Map<Integer,Integer> keepByGroup = new HashMap<>();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> scanStorage());
    private final ActivityResultLauncher<IntentSenderRequest> deleteLauncher =
        registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            Toast.makeText(this, result.getResultCode() == RESULT_OK ? "Files moved to Trash" : "Deletion cancelled", Toast.LENGTH_LONG).show();
            scanStorage();
        });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        showWelcome();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { showDashboard(); }
        });
    }

    private void basePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18),dp(20),dp(18),dp(90));
        scroll.addView(page);
        setContentView(scroll);
    }

    private void showWelcome() {
        basePage();
        TextView logo = text("SPACEWISE", 16, CORAL, true); page.addView(logo);
        page.addView(text("Free space. Keep what matters.", 34, TEXT, true), margins(0,28,0,12));
        page.addView(text("SpaceWise scans files locally on your phone. Your photos, videos and documents are never uploaded.", 16, MUTED, false));
        Button start = button("Allow access and scan phone");
        start.setOnClickListener(v -> requestAccess());
        page.addView(start, margins(0,28,0,12));
        page.addView(text("Android will ask permission separately for photos, videos and audio. You stay in control of every deletion.", 13, MUTED, false));
    }

    private void requestAccess() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        permissionLauncher.launch(permissions.toArray(new String[0]));
    }

    private boolean hasMediaAccess() {
        if (Build.VERSION.SDK_INT >= 33)
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void scanStorage() {
        if (!hasMediaAccess()) { showPermissionNeeded(); return; }
        basePage();
        page.addView(text("Scanning your phone…", 26, TEXT, true));
        progress = new ProgressBar(this); page.addView(progress, margins(0,28,0,12));
        status = text("Reading MediaStore safely", 14, MUTED, false); page.addView(status);
        worker.execute(() -> {
            photos.clear(); videos.clear(); audio.clear(); documents.clear(); apps.clear(); duplicateGroups.clear();
            queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Photos", photos);
            queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Videos", videos);
            queryMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Audio", audio);
            queryDocuments();
            queryApps();
            runOnUiThread(this::showDashboard);
        });
    }

    private void showPermissionNeeded() {
        basePage();
        page.addView(text("Storage access is required", 28, TEXT, true));
        page.addView(text("Without Android media permission, SpaceWise cannot see or analyse files on this phone.",16,MUTED,false), margins(0,14,0,20));
        Button retry=button("Grant permission"); retry.setOnClickListener(v->requestAccess()); page.addView(retry);
    }

    private void showDashboard() {
        if (photos.isEmpty() && videos.isEmpty() && audio.isEmpty() && documents.isEmpty() && !hasMediaAccess()) { showWelcome(); return; }
        basePage();
        LinearLayout top = row();
        top.addView(text("SpaceWise",25,TEXT,true), new LinearLayout.LayoutParams(0,-2,1));
        Button rescan = smallButton("Rescan"); rescan.setOnClickListener(v->scanStorage()); top.addView(rescan);
        page.addView(top);
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total=fs.getTotalBytes(), free=fs.getAvailableBytes(), used=total-free;
        page.addView(text(format(used)+" used",42,TEXT,true), margins(0,32,0,4));
        page.addView(text("of "+format(total)+" internal storage · "+format(free)+" free",16,MUTED,false));
        page.addView(categoryCard("▧","Photos",photos,CORAL), margins(0,26,0,10));
        page.addView(categoryCard("▶","Videos",videos,Color.rgb(255,154,82)), margins(0,0,0,10));
        page.addView(categoryCard("▤","Documents",documents,Color.rgb(139,112,248)), margins(0,0,0,10));
        page.addView(categoryCard("♫","Audio",audio,Color.rgb(91,82,75)), margins(0,0,0,10));
        page.addView(categoryCard("◉","Applications",apps,Color.rgb(91,82,75)), margins(0,0,0,24));
        page.addView(text("SMART CLEAN",13,MUTED,true), margins(0,8,0,10));
        Button dup=button("Find exact duplicate photos & videos");
        dup.setOnClickListener(v->findDuplicates());
        page.addView(dup);
        page.addView(text("Duplicates are confirmed using file size and SHA-256 content hashing. SpaceWise never deletes based only on the filename.",12,MUTED,false),margins(0,10,0,18));
        Button large=outlineButton("Review files larger than 100 MB");
        large.setOnClickListener(v->showLargeFiles());
        page.addView(large);
        if (!hasUsageAccess()) {
            Button usage=outlineButton("Enable unused-app analysis");
            usage.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            page.addView(usage,margins(0,10,0,0));
        }
    }

    private View categoryCard(String icon,String name,List<StorageItem> items,int color) {
        LinearLayout card=row(); card.setPadding(dp(18),dp(18),dp(18),dp(18)); card.setBackground(round(color,16));
        TextView ic=text(icon,26,Color.WHITE,true); card.addView(ic,new LinearLayout.LayoutParams(dp(44),-2));
        LinearLayout copy=new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(name,18,Color.WHITE,true)); copy.addView(text(items.size()+" files · "+format(totalSize(items)),14,0xfff5e8df,false));
        card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(text("›",30,Color.WHITE,false));
        card.setOnClickListener(v->showItems(name,items));
        return card;
    }

    private void showItems(String title,List<StorageItem> source) {
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(18),dp(18),0);
        root.setBackgroundColor(BG);
        page=root;
        addBack(title,source.size()+" items · "+format(totalSize(source)));
        List<StorageItem> sorted=new ArrayList<>(source);
        sorted.sort((a,b)->Long.compare(b.size,a.size));
        if(sorted.isEmpty()){
            page.addView(text("No files found in this category.",16,MUTED,false),margins(0,30,0,0));
            setContentView(root);
            return;
        }
        RecyclerView list=new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setHasFixedSize(true);
        list.setItemViewCacheSize(12);
        list.setAdapter(new MediaAdapter(sorted));
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void preview(StorageItem item) {
        try {
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(item.uri,item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch(Exception e){Toast.makeText(this,"No preview app is available for this file",Toast.LENGTH_LONG).show();}
    }

    private void showLargeFiles() {
        List<StorageItem> all=new ArrayList<>();all.addAll(photos);all.addAll(videos);all.addAll(audio);all.addAll(documents);
        all.removeIf(i->i.size<100L*1024*1024);showItems("Large files",all);
    }

    private void findDuplicates() {
        basePage();page.addView(text("Finding exact duplicates…",25,TEXT,true));page.addView(new ProgressBar(this),margins(0,24,0,12));
        TextView note=text("Checking same-size files, then verifying their content. This can take a few minutes for large videos.",14,MUTED,false);page.addView(note);
        worker.execute(()->{
            List<StorageItem> candidates=new ArrayList<>();candidates.addAll(photos);candidates.addAll(videos);
            Map<Long,List<StorageItem>> bySize=new HashMap<>();
            for(StorageItem i:candidates) if(i.size>0) bySize.computeIfAbsent(i.size,k->new ArrayList<>()).add(i);
            Map<String,List<StorageItem>> exact=new LinkedHashMap<>();
            int done=0;
            for(List<StorageItem> same:bySize.values()) if(same.size()>1) for(StorageItem i:same){
                String hash=hash(i.uri); if(hash!=null) exact.computeIfAbsent(i.size+":"+hash,k->new ArrayList<>()).add(i);
                final int count=++done;runOnUiThread(()->note.setText("Verified "+count+" possible duplicate files…"));
            }
            duplicateGroups.clear();for(List<StorageItem> group:exact.values()) if(group.size()>1) duplicateGroups.add(new DuplicateGroup(group));
            runOnUiThread(this::showDuplicates);
        });
    }

    private void showDuplicates() {
        basePage();long recover=0;for(DuplicateGroup g:duplicateGroups)for(int i=1;i<g.items.size();i++)recover+=g.items.get(i).size;
        addBack("Exact duplicates",duplicateGroups.size()+" groups · "+format(recover)+" recoverable");
        if(duplicateGroups.isEmpty()){page.addView(text("No exact duplicate photos or videos found.",17,GREEN,true),margins(0,30,0,0));return;}
        keepByGroup.clear();
        for(int gi=0;gi<duplicateGroups.size();gi++){
            DuplicateGroup group=duplicateGroups.get(gi);keepByGroup.put(gi,0);
            LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(14),dp(14),dp(14));box.setBackground(round(CARD,16));
            box.addView(text((group.items.get(0).mime.startsWith("video")?"Duplicate video":"Duplicate photo")+" · "+group.items.size()+" copies",17,TEXT,true));
            RadioGroup radios=new RadioGroup(this);radios.setOrientation(RadioGroup.VERTICAL);
            for(int i=0;i<group.items.size();i++){
                StorageItem item=group.items.get(i);RadioButton radio=new RadioButton(this);radio.setText((i==0?"Keep original: ":"Keep: ")+item.name+"\n"+item.path+" · "+format(item.size));radio.setTextColor(i==0?GREEN:TEXT);radio.setPadding(0,dp(8),0,dp(8));radio.setId(View.generateViewId());final int groupIndex=gi,itemIndex=i;radio.setOnCheckedChangeListener((b,checked)->{if(checked)keepByGroup.put(groupIndex,itemIndex);});radios.addView(radio);if(i==0)radio.setChecked(true);
                radio.setOnLongClickListener(v->{preview(item);return true;});
            }
            box.addView(radios);box.addView(text("Tap one copy to keep. Long-press a copy to preview it.",12,MUTED,false));page.addView(box,margins(0,0,0,12));
        }
        Button delete=button("Keep selected originals and delete all extra copies");
        delete.setOnClickListener(v->deleteDuplicateExtras());page.addView(delete,margins(0,12,0,0));
    }

    private void deleteDuplicateExtras() {
        List<Uri> remove=new ArrayList<>();
        for(int gi=0;gi<duplicateGroups.size();gi++){DuplicateGroup g=duplicateGroups.get(gi);int keep=keepByGroup.getOrDefault(gi,0);for(int i=0;i<g.items.size();i++)if(i!=keep)remove.add(g.items.get(i).uri);}
        if(remove.isEmpty())return;
        if(Build.VERSION.SDK_INT>=30){
            try{PendingIntent pending=MediaStore.createDeleteRequest(getContentResolver(),remove);deleteLauncher.launch(new IntentSenderRequest.Builder(pending.getIntentSender()).build());}
            catch(Exception e){Toast.makeText(this,"Android could not prepare deletion: "+e.getMessage(),Toast.LENGTH_LONG).show();}
        }else{
            int deleted=0;for(Uri uri:remove)try{deleted+=getContentResolver().delete(uri,null,null);}catch(Exception ignored){}
            Toast.makeText(this,deleted+" duplicate copies deleted",Toast.LENGTH_LONG).show();scanStorage();
        }
    }

    private void queryMedia(Uri collection,String category,List<StorageItem> out) {
        String[] projection={MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.MIME_TYPE,MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.DATE_MODIFIED};
        try(Cursor c=getContentResolver().query(collection,projection,null,null,MediaStore.MediaColumns.DATE_MODIFIED+" DESC")){
            if(c==null)return;int id=c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID),name=c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME),size=c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE),mime=c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE),path=c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH),date=c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            while(c.moveToNext())out.add(new StorageItem(Uri.withAppendedPath(collection,String.valueOf(c.getLong(id))),c.getString(name),c.getLong(size),safe(c.getString(mime)),safe(c.getString(path)),c.getLong(date),true));
        }catch(Exception ignored){}
    }

    private void queryDocuments() {
        Uri collection=MediaStore.Files.getContentUri("external");
        String[] p={MediaStore.Files.FileColumns._ID,MediaStore.Files.FileColumns.DISPLAY_NAME,MediaStore.Files.FileColumns.SIZE,MediaStore.Files.FileColumns.MIME_TYPE,MediaStore.Files.FileColumns.RELATIVE_PATH,MediaStore.Files.FileColumns.DATE_MODIFIED};
        String selection=MediaStore.Files.FileColumns.MEDIA_TYPE+"=?";
        try(Cursor c=getContentResolver().query(collection,p,selection,new String[]{String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)},MediaStore.Files.FileColumns.DATE_MODIFIED+" DESC")){
            if(c==null)return;while(c.moveToNext()){String mime=safe(c.getString(3)),name=safe(c.getString(1));if(isDocument(mime,name))documents.add(new StorageItem(Uri.withAppendedPath(collection,String.valueOf(c.getLong(0))),name,c.getLong(2),mime,safe(c.getString(4)),c.getLong(5),false));}
        }catch(Exception ignored){}
    }

    private boolean isDocument(String mime,String name) {
        return mime.startsWith("application/")||mime.startsWith("text/")||name.matches("(?i).+\\.(pdf|docx?|xlsx?|pptx?|txt|zip|rar|csv)$");
    }

    private void queryApps() {
        PackageManager pm=getPackageManager();Map<String,Long> lastUsed=usageTimes();
        for(ApplicationInfo ai:pm.getInstalledApplications(PackageManager.GET_META_DATA)){
            if((ai.flags&ApplicationInfo.FLAG_SYSTEM)!=0)continue;File apk=new File(ai.sourceDir);String label=String.valueOf(pm.getApplicationLabel(ai));long last=lastUsed.getOrDefault(ai.packageName,0L);
            apps.add(new StorageItem(Uri.parse("package:"+ai.packageName),label,apk.length(),"application/vnd.android.package-archive",last==0?"Usage unavailable":daysAgo(last)+" days since last use",last,false));
        }
    }

    private Map<String,Long> usageTimes() {
        Map<String,Long> map=new HashMap<>();if(!hasUsageAccess())return map;UsageStatsManager usm=(UsageStatsManager)getSystemService(USAGE_STATS_SERVICE);long now=System.currentTimeMillis();
        for(UsageStats s:usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY,now-365L*86400000L,now))map.put(s.getPackageName(),s.getLastTimeUsed());return map;
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops=(AppOpsManager)getSystemService(APP_OPS_SERVICE);int mode=ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),getPackageName());return mode==AppOpsManager.MODE_ALLOWED;
    }

    private String hash(Uri uri) {
        try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)return null;MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[64*1024];int n;while((n=in.read(buf))>0)md.update(buf,0,n);StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format("%02x",b));return s.toString();}catch(Exception e){return null;}
    }

    private void addBack(String title,String subtitle) {
        Button back=smallButton("← Overview");back.setOnClickListener(v->showDashboard());page.addView(back);
        page.addView(text(title,30,TEXT,true),margins(0,22,0,5));page.addView(text(subtitle,14,MUTED,false),margins(0,0,0,22));
    }

    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setTypeface(null,bold?1:0);v.setLineSpacing(0,1.15f);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(15);b.setAllCaps(false);b.setBackground(round(CORAL,13));b.setPadding(dp(14),0,dp(14),0);b.setMinHeight(dp(58));return b;}
    private Button outlineButton(String s){Button b=button(s);b.setBackground(round(CARD,13));return b;}
    private Button smallButton(String s){Button b=button(s);b.setTextSize(12);b.setMinHeight(dp(42));return b;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout.LayoutParams margins(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private long totalSize(List<StorageItem> x){long n=0;for(StorageItem i:x)n+=i.size;return n;}
    private String format(long bytes){if(bytes<1024)return bytes+" B";double v=bytes;String[] u={"B","KB","MB","GB","TB"};int i=0;while(v>=1024&&i<u.length-1){v/=1024;i++;}return new DecimalFormat(v>=10?"0.0":"0.00").format(v)+" "+u[i];}
    private String safe(String x){return x==null?"":x;}
    private long daysAgo(long time){return Math.max(0,(System.currentTimeMillis()-time)/86400000L);}

    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}

    private class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.Holder> {
        private final List<StorageItem> items;
        MediaAdapter(List<StorageItem> values){items=values;}
        @Override public Holder onCreateViewHolder(ViewGroup parent,int viewType){
            LinearLayout card=row();
            card.setPadding(dp(10),dp(9),dp(10),dp(9));
            card.setBackground(round(CARD,14));
            RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(-1,dp(86));
            params.setMargins(0,0,0,dp(9));
            card.setLayoutParams(params);
            ImageView image=new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xff55463e);
            card.addView(image,new LinearLayout.LayoutParams(dp(68),dp(68)));
            LinearLayout copy=new LinearLayout(MainActivity.this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setGravity(Gravity.CENTER_VERTICAL);
            copy.setPadding(dp(12),0,dp(6),0);
            TextView name=text("",15,TEXT,true);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView meta=text("",12,MUTED,false);
            meta.setMaxLines(2);
            copy.addView(name);
            copy.addView(meta);
            card.addView(copy,new LinearLayout.LayoutParams(0,-1,1));
            Button open=smallButton("View");
            card.addView(open,new LinearLayout.LayoutParams(dp(68),dp(46)));
            return new Holder(card,image,name,meta,open);
        }
        @Override public void onBindViewHolder(Holder h,int position){
            StorageItem item=items.get(position);
            h.name.setText(item.name);
            h.meta.setText(format(item.size)+" · "+item.path);
            h.open.setOnClickListener(v->preview(item));
            h.itemView.setOnClickListener(v->preview(item));
            if(item.media){
                Glide.with(MainActivity.this)
                    .load(item.uri)
                    .override(180,180)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_report_image)
                    .into(h.image);
            }else{
                Glide.with(MainActivity.this).clear(h.image);
                h.image.setImageResource(item.mime.contains("package")?android.R.drawable.sym_def_app_icon:android.R.drawable.ic_menu_save);
            }
        }
        @Override public void onViewRecycled(Holder h){
            Glide.with(MainActivity.this).clear(h.image);
            super.onViewRecycled(h);
        }
        @Override public int getItemCount(){return items.size();}
        class Holder extends RecyclerView.ViewHolder{
            final ImageView image;final TextView name,meta;final Button open;
            Holder(View root,ImageView i,TextView n,TextView m,Button o){super(root);image=i;name=n;meta=m;open=o;}
        }
    }

    static class StorageItem {
        final Uri uri;final String name;final long size;final String mime,path;final long modified;final boolean media;
        StorageItem(Uri u,String n,long s,String m,String p,long d,boolean me){uri=u;name=n;size=s;mime=m;path=p;modified=d;media=me;}
    }
    static class DuplicateGroup {final List<StorageItem> items;DuplicateGroup(List<StorageItem> x){items=x;items.sort((a,b)->Long.compare(a.modified,b.modified));}}
}
