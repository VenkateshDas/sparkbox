#!/usr/bin/env python3
"""Processing regressions without a phone, vault, API key, or paid request.

Run ./gradlew captureTest first. Android callback stubs exercise production
BackgroundJob; visual checkpoints exercise production Visuals with fake I/O.
These checks do not qualify Android/OEM scheduling on a physical phone.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA_HOME = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk"))


def tool(name):
    local = JAVA_HOME / "bin" / name
    return str(local) if local.exists() else shutil.which(name) or name


PAGE_PROBE = """package me.venkatesh.saveobsidian;
public class ProcessingProbe {
 public static void main(String[] args) throws Exception {
  try { Web.get("https://sparkbox-investigation.invalid/article"); throw new AssertionError("Expected DNS failure"); }
  catch (java.io.IOException e) {
   if (!Net.offline(e)) throw new AssertionError("DNS failure became an invalid URL", e);
  }
  if (PageTitle.publicHost("127.0.0.1") || PageTitle.publicHost("192.168.1.1") || PageTitle.publicHost("::1"))
   throw new AssertionError("Private hosts must remain blocked");
  Extract.Page page = Extract.parse("<article><p>" + "An event wakes the worker to read pending rows. ".repeat(10)
   + "</p><figure><img src='/diagram.png' alt='Pending jobs table'></figure></article>", "https://example.com/article");
  if (page.media.size()!=1 || !page.media.get(0).url.equals("https://example.com/diagram.png"))
   throw new AssertionError("Article diagram discarded");
  System.out.println("DNS classification, private-host protection and article media passed");
 }
}
"""

BACKGROUND_STUBS = {
 "android/content/Context.java": """package android.content; public class Context {
  public final android.app.job.JobScheduler scheduler = new android.app.job.JobScheduler();
  public <T> T getSystemService(Class<T> t) {return t.cast(scheduler);}
 }""",
 "android/content/ComponentName.java": "package android.content; public class ComponentName {public ComponentName(Context c,Class<?> k){}}",
 "android/content/Intent.java": "package android.content; public class Intent {public static final String ACTION_BOOT_COMPLETED=\"boot\",ACTION_MY_PACKAGE_REPLACED=\"update\";public String getAction(){return ACTION_BOOT_COMPLETED;}}",
 "android/content/BroadcastReceiver.java": "package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}",
 "android/os/Build.java": "package android.os; public class Build {public static class VERSION {public static int SDK_INT=35;}}",
 "android/util/Log.java": "package android.util; public class Log {public static int w(String t,String s){return 0;} public static int w(String t,String s,Throwable e){return 0;}}",
 "android/app/job/JobInfo.java": """package android.app.job; public class JobInfo {
  public static final int NETWORK_TYPE_ANY=1; public int id; public long delay; public boolean persisted,expedited;
  public long getMinLatencyMillis(){return delay;}
  public static class Builder { final JobInfo j=new JobInfo();
   public Builder(int id,android.content.ComponentName c){j.id=id;}
   public Builder setRequiredNetworkType(int t){return this;}
   public Builder setMinimumLatency(long d){j.delay=d;return this;}
   public Builder setPersisted(boolean b){j.persisted=b;return this;}
   public Builder setExpedited(boolean b){j.expedited=b;return this;}
   public JobInfo build(){return j;}
  }
 }""",
 "android/app/job/JobScheduler.java": """package android.app.job; public class JobScheduler {
  public static final int RESULT_SUCCESS=1;
  public final java.util.Map<Integer,JobInfo> jobs=new java.util.concurrent.ConcurrentHashMap<>();
  public volatile int expeditedFailures;
  public JobInfo getPendingJob(int id){return jobs.get(id);}
  public int schedule(JobInfo j){if(j.expedited && expeditedFailures-->0)return 0; jobs.put(j.id,j);return 1;}
 }""",
 "android/app/job/JobParameters.java": "package android.app.job; public class JobParameters {final int id;public JobParameters(int i){id=i;}public int getJobId(){return id;}}",
 "android/app/job/JobService.java": """package android.app.job; public class JobService extends android.content.Context {
  public final java.util.Set<Integer> finished=java.util.concurrent.ConcurrentHashMap.newKeySet();
  public boolean onStartJob(JobParameters p){return true;} public boolean onStopJob(JobParameters p){return true;}
  public void jobFinished(JobParameters p,boolean retry){finished.add(p.getJobId());}
 }""",
 "me/venkatesh/saveobsidian/Model.java": "package me.venkatesh.saveobsidian; class Model {static String key(android.content.Context c){return \"fake\";}}",
 "me/venkatesh/saveobsidian/Ingest.java": "package me.venkatesh.saveobsidian; class Ingest {static long retryDelay(android.content.Context c){return -1;}}",
 "me/venkatesh/saveobsidian/Tagger.java": """package me.venkatesh.saveobsidian; class Tagger {
  static final java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
  static volatile Cancel owner; static final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
  static void run(android.content.Context c){run(c,new Cancel());}
  static void run(android.content.Context c,Cancel token){calls.incrementAndGet();owner=token;started.countDown();try{release.await();}catch(Exception e){}}
  static boolean waiting(android.content.Context c){return false;}
 }""",
 "me/venkatesh/saveobsidian/BackgroundProbe.java": """package me.venkatesh.saveobsidian;
 public class BackgroundProbe {
  public static void main(String[] args)throws Exception {
   BackgroundJob job=new BackgroundJob();job.scheduler.expeditedFailures=1;
   BackgroundJob.now(job);Thread.sleep(80);
   if(Tagger.calls.get()!=0)throw new AssertionError("Processing starts outside Android job ownership");
   android.app.job.JobInfo first=job.scheduler.jobs.get(1);
   if(first==null || !first.persisted || first.expedited)throw new AssertionError("Persisted ordinary fallback missing");
   android.app.job.JobParameters one=new android.app.job.JobParameters(1),two=new android.app.job.JobParameters(2);
   try {
    job.onStartJob(one);
    if(!Tagger.started.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Worker did not start");
    job.onStartJob(two);
    if(job.finished.contains(1) || Tagger.calls.get()!=1)throw new AssertionError("Active owner finished early or workers overlap");
    job.onStopJob(two);
    if(Tagger.owner.stopped())throw new AssertionError("Unrelated callback stopped active owner");
    BackgroundJob.now(job);
    job.onStopJob(new android.app.job.JobParameters(1));
    if(!Tagger.owner.stopped())throw new AssertionError("Owner stop did not cancel transport");
   } finally {Tagger.release.countDown();}
   long end=System.nanoTime()+2_000_000_000L;
   while(!job.scheduler.jobs.containsKey(2) && System.nanoTime()<end)Thread.sleep(10);
   if(!job.scheduler.jobs.containsKey(2) || !job.scheduler.jobs.get(2).persisted)throw new AssertionError("Successor wakeup missing");
   System.out.println("Job ownership, stop isolation, quota fallback and persisted successor passed");
  }
 }""",
}

VISUAL_STUBS = {
 "android/content/Context.java": "package android.content; public class Context {}",
 "me/venkatesh/saveobsidian/Content.java": """package me.venkatesh.saveobsidian; class Content {
  static org.json.JSONObject data=new org.json.JSONObject();static String text="";
  static org.json.JSONObject media(android.content.Context c,String u){return new org.json.JSONObject(data.toString());}
  static void saveMedia(android.content.Context c,String u,org.json.JSONObject v){data=new org.json.JSONObject(v.toString());}
  static void save(android.content.Context c,String u,String t){text=t;}
 }""",
 "me/venkatesh/saveobsidian/Fetch.java": "package me.venkatesh.saveobsidian; class Fetch {static class Result {String text=\"page text\",title=\"slides\";final java.util.List<Extract.Media> media=new java.util.ArrayList<>();}}",
 "me/venkatesh/saveobsidian/Web.java": """package me.venkatesh.saveobsidian; class Web {
  static class Reply {int code=200;String type="image/png";byte[] body={1,2,3};}
  static Reply get(String u,int max){return new Reply();}
 }""",
 "me/venkatesh/saveobsidian/Attach.java": "package me.venkatesh.saveobsidian; class Attach {static byte[] jpeg(byte[] b,int edge){return b;}}",
 "me/venkatesh/saveobsidian/Tagger.java": "package me.venkatesh.saveobsidian; class Tagger {static boolean halt(){return false;}}",
 "me/venkatesh/saveobsidian/Model.java": """package me.venkatesh.saveobsidian; class Model {
  static int calls;
  static String readImage(String key,byte[] jpeg,String context){if(jpeg.length!=3)throw new AssertionError("Missing image bytes");calls++;return "Slide "+calls+": "+"visible detail ".repeat(300);}
  static String watch(String k,byte[] b,String m){throw new AssertionError("Unexpected video");}
 }""",
 "me/venkatesh/saveobsidian/VisualProbe.java": """package me.venkatesh.saveobsidian; public class VisualProbe {
  public static void main(String[]args)throws Exception {
   android.content.Context c=new android.content.Context();Fetch.Result page=new Fetch.Result();
   for(int i=0;i<5;i++)page.media.add(new Extract.Media("https://example.com/"+i,"caption",false));
   String source="https://www.instagram.com/p/Slides/";
   try{Visuals.read(c,"fake",source,page);throw new AssertionError("Expected bounded slice");}catch(Net.Deferred expected){}
   if(Model.calls!=3 || Visuals.complete(c,source))throw new AssertionError("Incorrect partial checkpoint");
   for(int i=0;i<page.media.size();i++)page.media.set(i,new Extract.Media("https://rotated.example.com/"+i+"?signature=new","caption",false));
   Visuals.read(c,"fake",source,page);
   if(Model.calls!=5 || !Visuals.complete(c,source))throw new AssertionError("Paid results not reused");
   Visuals.read(c,"fake",source,page);
   if(Model.calls!=5)throw new AssertionError("Duplicate visual call");
   String prompt=Visuals.prompt(c,source,1000);
   for(int i=1;i<=5;i++)if(!prompt.contains("Slide "+i))throw new AssertionError("Later slide omitted from plan");
   if(!Content.text.contains("AI interpretation") || !prompt.contains("page text"))throw new AssertionError("Provenance or page text lost");
   System.out.println("Visual bytes, bounded slices, checkpoint reuse and all-slide plan coverage passed");
  }
 }""",
}


def run(*args):
    subprocess.run(args, cwd=ROOT, check=True)


def main():
    if not (ROOT / "build/gradle/captureTest").exists():
        raise SystemExit("Run ./gradlew captureTest first")
    classpath = os.pathsep.join([str(ROOT / "build/gradle/captureTest"), str(ROOT / "lib/*")])
    source = ROOT / "src/me/venkatesh/saveobsidian"
    with tempfile.TemporaryDirectory(prefix="sparkbox-processing-") as folder:
        work = Path(folder)
        page = work / "ProcessingProbe.java"
        page.write_text(PAGE_PROBE)
        run(tool("javac"), "-cp", classpath, "-d", str(work),
            str(source / "PageTitle.java"), str(source / "Web.java"), str(page))
        run(tool("java"), "-cp", str(work) + os.pathsep + classpath,
            "me.venkatesh.saveobsidian.ProcessingProbe")
        for label, stubs, production, entry in (
            ("background", BACKGROUND_STUBS, "BackgroundJob", "BackgroundProbe"),
            ("visuals", VISUAL_STUBS, "Visuals", "VisualProbe"),
        ):
            files = []
            for name, content in stubs.items():
                path = work / label / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                files.append(str(path))
            out = work / label / "classes"
            run(tool("javac"), "-cp", classpath, "-d", str(out), *files, str(source / f"{production}.java"))
            run(tool("java"), "-cp", str(out) + os.pathsep + classpath,
                f"me.venkatesh.saveobsidian.{entry}")


if __name__ == "__main__":
    main()
