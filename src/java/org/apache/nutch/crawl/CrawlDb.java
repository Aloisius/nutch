/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nutch.crawl;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

// Commons Logging imports
import org.apache.hadoop.fs.s3native.NativeS3FileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.io.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.*;

import org.apache.nutch.util.HadoopFSUtil;
import org.apache.nutch.util.LockUtil;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.NutchJob;
import org.apache.nutch.util.TimingUtil;

/**
 * This class takes the output of the fetcher and updates the
 * crawldb accordingly.
 */
public class CrawlDb extends Configured implements Tool {
  public static final Logger LOG = LoggerFactory.getLogger(CrawlDb.class);

  public static final String CRAWLDB_ADDITIONS_ALLOWED = "db.update.additions.allowed";

  public static final String CRAWLDB_PURGE_404 = "db.update.purge.404";

  public static final String CURRENT_NAME = "current";
  
  public static final String LOCK_NAME = ".locked";
  
  public CrawlDb() {}
  
  public CrawlDb(Configuration conf) {
    setConf(conf);
  }

  public void update(Path crawlDb, Path[] segments, boolean normalize, boolean filter) throws IOException {
    boolean additionsAllowed = getConf().getBoolean(CRAWLDB_ADDITIONS_ALLOWED, true);
    update(crawlDb, segments, normalize, filter, additionsAllowed, false, true, CrawlDb.CURRENT_NAME, crawlDb);
  }
  
  public void update(Path crawlDb, Path[] segments, boolean normalize, boolean filter, boolean additionsAllowed,
                     boolean force, boolean install, String dbVersion, Path outputDir) throws IOException {
    FileSystem fs = outputDir.getFileSystem(getConf());
    Path lock = new Path(outputDir, LOCK_NAME);
    LockUtil.createLockFile(fs, lock, force);
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    long start = System.currentTimeMillis();

    JobConf job = CrawlDb.createJob(getConf(), crawlDb, dbVersion, outputDir);
    job.setBoolean(CRAWLDB_ADDITIONS_ALLOWED, additionsAllowed);
    job.setBoolean(CrawlDbFilter.URL_FILTERING, filter);
    job.setBoolean(CrawlDbFilter.URL_NORMALIZING, normalize);

    boolean url404Purging = job.getBoolean(CRAWLDB_PURGE_404, false);

    if (LOG.isInfoEnabled()) {
      LOG.info("CrawlDb update: starting at " + sdf.format(start));
      LOG.info("CrawlDb update: db: " + crawlDb);
      LOG.info("CrawlDb update: segments: " + Arrays.asList(segments));
      LOG.info("CrawlDb update: additions allowed: " + additionsAllowed);
      LOG.info("CrawlDb update: URL normalizing: " + normalize);
      LOG.info("CrawlDb update: URL filtering: " + filter);
      LOG.info("CrawlDb update: 404 purging: " + url404Purging);
    }

    for (int i = 0; i < segments.length; i++) {
      FileSystem sfs = segments[i].getFileSystem(getConf());
      Path fetch = new Path(segments[i], CrawlDatum.FETCH_DIR_NAME);
      Path parse = new Path(segments[i], CrawlDatum.PARSE_DIR_NAME);
      if (sfs.exists(fetch)) {
        FileInputFormat.addInputPath(job, fetch);
        if (sfs.exists(parse)) {
          FileInputFormat.addInputPath(job, parse);
        }
      } else {
        LOG.info(" - skipping invalid segment " + segments[i]);
      }
    }

    if (LOG.isInfoEnabled()) {
      LOG.info("CrawlDb update: Merging segment data into db.");
    }
    try {
      JobClient.runJob(job);
    } catch (IOException e) {
      LockUtil.removeLockFile(fs, lock);
      Path outPath = FileOutputFormat.getOutputPath(job);
      if (fs.exists(outPath) ) fs.delete(outPath, true);
      throw e;
    }

    if (install) {
     CrawlDb.install(job, crawlDb);
    }

    long end = System.currentTimeMillis();
    LOG.info("CrawlDb update: finished at " + sdf.format(end) + ", elapsed: " + TimingUtil.elapsedTime(start, end));
  }

  public static JobConf createJob(Configuration config, Path crawlDb) throws IOException {
    return createJob(config, crawlDb, CURRENT_NAME, crawlDb);
  }

  public static JobConf createJob(Configuration config, Path crawlDb, String dbVersion, Path outputDir)
    throws IOException {
    Path newCrawlDb =
      new Path(outputDir,
               Integer.toString(new Random().nextInt(Integer.MAX_VALUE)));

    JobConf job = new NutchJob(config);
    job.setJobName("crawldb " + crawlDb);

    Path current = new Path(crawlDb, dbVersion);
    if (current.getFileSystem(job).exists(current)) {
      FileInputFormat.addInputPath(job, current);
    }
    job.setInputFormat(SequenceFileInputFormat.class);

    job.setMapperClass(CrawlDbFilter.class);
    job.setReducerClass(CrawlDbReducer.class);

    FileOutputFormat.setOutputPath(job, newCrawlDb);
    job.setOutputFormat(MapFileOutputFormat.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(CrawlDatum.class);

    // S3 driver does an MD5 verification after uploading
    // Also, this is painfully slow because of S3's slow copy functions
    FileSystem fs = newCrawlDb.getFileSystem(config);
    if (fs instanceof NativeS3FileSystem || fs.getScheme().equals("s3a")) {
      LOG.info("Fetcher: Setting null output committer for " + fs.getScheme());
      job.setOutputCommitter(NullOutputCommitter.class);
    }

    // https://issues.apache.org/jira/browse/NUTCH-1110
    job.setBoolean("mapreduce.fileoutputcommitter.marksuccessfuljobs", false);

    return job;
  }

  public static void install(JobConf job, Path crawlDb) throws IOException {
    boolean preserveBackup = job.getBoolean("db.preserve.backup", true);

    Path newCrawlDb = FileOutputFormat.getOutputPath(job);
    FileSystem fs = newCrawlDb.getFileSystem(job);
    Path old = new Path(crawlDb, "old");
    Path current = new Path(crawlDb, CURRENT_NAME);
    if (fs.exists(current)) {
      if (fs.exists(old)) fs.delete(old, true);
      fs.rename(current, old);
    }
    fs.mkdirs(crawlDb);
    fs.rename(newCrawlDb, current);
    if (!preserveBackup && fs.exists(old)) fs.delete(old, true);
    Path lock = new Path(crawlDb, LOCK_NAME);
    LockUtil.removeLockFile(fs, lock);
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(NutchConfiguration.create(), new CrawlDb(), args);
    System.exit(res);
  }

  public int run(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: CrawlDb <crawldb> (-dir <segments> | <seg1> <seg2> ...) [-force] [-normalize] [-filter] [-noAdditions] [-noInstall] [-dbversion ver] [-output crawldb]");
      System.err.println("\tcrawldb\tCrawlDb to update");
      System.err.println("\t-dir segments\tparent directory containing all segments to update from");
      System.err.println("\tseg1 seg2 ...\tlist of segment names to update from");
      System.err.println("\t-force\tforce update even if CrawlDb appears to be locked (CAUTION advised)");
      System.err.println("\t-normalize\tuse URLNormalizer on urls in CrawlDb and segment (usually not needed)");
      System.err.println("\t-filter\tuse URLFilters on urls in CrawlDb and segment");
      System.err.println("\t-noAdditions\tonly update already existing URLs, don't add any newly discovered URLs");
      System.err.println("\t-noInstall\tdon't rename output directory to current and previous to old");
      System.err.println("\t-dbVersion ver\tupdate using a specific crawldb/ver directory");
      System.err.println("\t-crawldb crawldb\toutput to crawldb instead of input dir");

      return -1;
    }
    boolean normalize = false;
    boolean filter = false;
    boolean force = false;
    boolean url404Purging = false;
    boolean install = true;
    boolean additionsAllowed = getConf().getBoolean(CRAWLDB_ADDITIONS_ALLOWED, true);
    String dbVersion = CURRENT_NAME;
    Path outputDir = new Path(args[0]);

    HashSet<Path> dirs = new HashSet<Path>();
    for (int i = 1; i < args.length; i++) {
      if (args[i].equals("-normalize")) {
        normalize = true;
      } else if (args[i].equals("-filter")) {
        filter = true;
      } else if (args[i].equals("-force")) {
        force = true;
      } else if (args[i].equals("-noAdditions")) {
        additionsAllowed = false;
      } else if (args[i].equals("-dir")) {
        Path dirPath = new Path(args[++i]);
        FileSystem fs = dirPath.getFileSystem(getConf());
        FileStatus[] statuses = fs.listStatus(dirPath);
        for (FileStatus status : statuses) {
          if (status.isDirectory()) {
            dirs.add(status.getPath());
          }
        }
      } else if ("-dbVersion".equals(args[i])) {
        dbVersion = args[++i];
      } else if ("-output".equals(args[i])) {
        outputDir = new Path(args[++i]);
      } else if (args[i].equals("-noInstall")) {
        install = false;
      } else {
        dirs.add(new Path(args[i]));
      }
    }
    try {
      update(new Path(args[0]), dirs.toArray(new Path[dirs.size()]), normalize, filter, additionsAllowed, force,
          install, dbVersion, outputDir);
      return 0;
    } catch (Exception e) {
      LOG.error("CrawlDb update: " + StringUtils.stringifyException(e));
      return -1;
    }
  }
}
