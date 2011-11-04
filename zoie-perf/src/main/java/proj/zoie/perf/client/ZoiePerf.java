package proj.zoie.perf.client;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.Version;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.ResourceHandler;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;

import proj.zoie.api.DefaultDirectoryManager;
import proj.zoie.api.DirectoryManager;
import proj.zoie.api.DirectoryManager.DIRECTORY_MODE;
import proj.zoie.api.IndexReaderFactory;
import proj.zoie.api.LifeCycleCotrolledDataConsumer;
import proj.zoie.api.impl.ZoieMergePolicy;
import proj.zoie.api.indexing.IndexReaderDecorator;
import proj.zoie.impl.indexing.DefaultIndexReaderDecorator;
import proj.zoie.impl.indexing.SimpleReaderCache;
import proj.zoie.impl.indexing.ZoieConfig;
import proj.zoie.impl.indexing.ZoieSystem;
import proj.zoie.impl.indexing.luceneNRT.ThrottledLuceneNRTDataConsumer;
import proj.zoie.perf.indexing.LinedFileDataProvider;
import proj.zoie.perf.indexing.TweetInterpreter;
import proj.zoie.perf.servlet.ZoiePerfServlet;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.GaugeMetric;
import com.yammer.metrics.core.MeterMetric;
import com.yammer.metrics.core.Metric;
import com.yammer.metrics.core.TimerMetric;
import com.yammer.metrics.reporting.CsvReporter;

public class ZoiePerf {

	private static class PerfTestHandler {
		final LifeCycleCotrolledDataConsumer<String> consumer;
		final IndexReaderFactory readerFactory;

		PerfTestHandler(LifeCycleCotrolledDataConsumer<String> consumer,
				IndexReaderFactory<IndexReader> readerFactory) {
			this.consumer = consumer;
			this.readerFactory = readerFactory;
		}
	}

	static TweetInterpreter interpreter = new TweetInterpreter();

	static Map<String,DIRECTORY_MODE> modeMap = new HashMap<String,DIRECTORY_MODE>();
	static{
		modeMap.put("file", DIRECTORY_MODE.SIMPLE);
		modeMap.put("mmap", DIRECTORY_MODE.MMAP);
		modeMap.put("nio", DIRECTORY_MODE.NIO);
	}
	
	static PerfTestHandler buildZoieHandler(File idxDir, Configuration topConf,Configuration conf) {

		ZoieConfig zoieConfig = new ZoieConfig();
		zoieConfig.setAnalyzer(new StandardAnalyzer(Version.LUCENE_34));
		zoieConfig.setBatchSize(10000);
		zoieConfig.setBatchDelay(10000);
		zoieConfig.setMaxBatchSize(10000);
		zoieConfig.setRtIndexing(true);
		zoieConfig.setVersionComparator(ZoiePerfVersion.COMPARATOR);
		zoieConfig.setReadercachefactory(SimpleReaderCache.FACTORY);
		
		String modeConf = topConf.getString("perf.directory.type", "file");
		DIRECTORY_MODE mode = modeMap.get(modeConf);
		if (mode == null) mode = DIRECTORY_MODE.SIMPLE;
		DirectoryManager dirMgr = new DefaultDirectoryManager(idxDir,mode);

		IndexReaderDecorator<IndexReader> indexReaderDecorator = new DefaultIndexReaderDecorator();

		ZoieSystem<IndexReader, String> zoieSystem = new ZoieSystem<IndexReader, String>(
				dirMgr, interpreter, indexReaderDecorator, zoieConfig);
		return new PerfTestHandler(
				(LifeCycleCotrolledDataConsumer<String>) zoieSystem,
				(IndexReaderFactory) zoieSystem);
	}

	static PerfTestHandler buildNrtHandler(File idxDir, Configuration topConf,Configuration conf)
			throws Exception {
		long throttle = conf.getLong("throttle");
		String modeConf = topConf.getString("perf.directory.type", "file");
		Directory dir;
		if ("file".equals(modeConf)){
			dir = FSDirectory.open(idxDir);
		}
		else if ("mmap".equals(modeConf)){
			dir = MMapDirectory.open(idxDir);
		}
		else if ("nio".equals(modeConf)){
			dir = NIOFSDirectory.open(idxDir);
		}
		else{
			dir = FSDirectory.open(idxDir);
		}
		
		MergePolicy mergePolicy = null;
		
		String mergePolicyConf = conf.getString("mergePolicy");
		if ("tier".equals(mergePolicyConf)){
			mergePolicy = new TieredMergePolicy();
		}
		else if ("zoie".equals(mergePolicyConf)){
			mergePolicy = new ZoieMergePolicy();
		}
		ThrottledLuceneNRTDataConsumer<String> nrtSystem = new ThrottledLuceneNRTDataConsumer<String>(
				dir, new StandardAnalyzer(Version.LUCENE_34),interpreter, throttle,mergePolicy);
		
		boolean appendOnly = conf.getBoolean("appendOnly", false);
		nrtSystem.setAppendOnly(appendOnly);
		
		return new PerfTestHandler(
				(LifeCycleCotrolledDataConsumer<String>) nrtSystem,
				(IndexReaderFactory<IndexReader>) nrtSystem);
	}

	static PerfTestHandler buildPerfHandler(Configuration conf)
			throws Exception {
		File idxDir = new File(conf.getString("perf.idxDir"));

		String type = conf.getString("perf.type");
		Configuration subConf = conf.subset("perf." + type);

		if ("zoie".equals(type)) {
			return buildZoieHandler(idxDir,conf, subConf);
		} else if ("nrt".equals(type)) {
			return buildNrtHandler(idxDir, conf,subConf);
		} else {
			throw new ConfigurationException("test type: " + type
					+ " is not supported");
		}
	}

	
	public static void runPerf(Configuration conf) throws Exception{
        Map<String,Metric> monitoredMetrics = new HashMap<String,Metric>();
		
		File queryFile = new File(conf.getString("perf.query.file"));
		if (!queryFile.exists()) {
			System.out.println("query file does not exist");
		}

		List<String> queryTermList = TermFileBuilder.loadFile(queryFile);
		final String[] queryTerms = queryTermList.toArray(new String[0]);

		final Random rand = new Random(System.currentTimeMillis());

		final PerfTestHandler testHandler = buildPerfHandler(conf);


		boolean doSearchTest = conf.getBoolean("perf.test.search", true);
		
		
		final TimerMetric searchTimer = Metrics.newTimer(ZoiePerf.class,
				"searchTimer", TimeUnit.MILLISECONDS, TimeUnit.SECONDS);
		final MeterMetric errorMeter = Metrics.newMeter(ZoiePerf.class,
				"errorMeter", "error", TimeUnit.SECONDS);
		
		monitoredMetrics.put("searchTimer", searchTimer);
		monitoredMetrics.put("errorMeter", errorMeter);

		final long waitTime = conf.getLong("perf.query.threadWait", 200);

		final class SearchThread extends Thread {

			private volatile boolean stop = false;

			public void terminate() {
				stop = true;
				synchronized(this){
				  this.notifyAll();
				}
			}

			public void run() {
				while (!stop) {
					int qidx = rand.nextInt(queryTerms.length);
					final TermQuery tq = new TermQuery(new Term("contents",
							queryTerms[qidx]));
					try {
						searchTimer.time(new Callable<TopDocs>() {

							@Override
							public TopDocs call() throws Exception {
								List<IndexReader> readers = null;
								IndexSearcher searcher = null;
								try {
									readers = testHandler.readerFactory
											.getIndexReaders();
									MultiReader reader = new MultiReader(
											readers.toArray(new IndexReader[0]),
											false);
									searcher = new IndexSearcher(reader);
									return searcher.search(tq, 10);
								} finally {
									if (searcher != null) {
										try {
											searcher.close();
										} catch (IOException e) {

										}
									}
									if (readers != null) {
										testHandler.readerFactory
												.returnIndexReaders(readers);
									}
								}
							}

						});
					} catch (Exception e) {
						errorMeter.mark();
					}
					
					synchronized(this){
						try {
							this.wait(waitTime);
						} catch (InterruptedException e) {
							continue;
						}
					}
				}
			}
		}

		File inputFile = new File(conf.getString("perf.input"));

		if (!inputFile.exists()) {
			throw new ConfigurationException("input file: "
					+ inputFile.getAbsolutePath() + " does not exist.");
		}

		long maxSize = conf.getLong("perf.maxSize");

		final LinedFileDataProvider dataProvider = new LinedFileDataProvider(
				inputFile, 0L);

		testHandler.consumer.start();

		long dataAmount;
		final long start = System.currentTimeMillis();

		Metric metric = null;
		String name = "eventCount";
		metric = Metrics.newGauge(ZoiePerf.class, name, new GaugeMetric<Long>() {

			@Override
			public Long value() {
				return dataProvider.getEventCount();
			}

		});
		
		monitoredMetrics.put(name, metric);

		name = "amountConsumed";
		metric = Metrics.newGauge(ZoiePerf.class, name,
				new GaugeMetric<Long>() {

					@Override
					public Long value() {
						ZoiePerfVersion ver = ZoiePerfVersion.fromString(testHandler.consumer.getVersion());
						return ver.offsetVersion;
					}

				});


		monitoredMetrics.put(name, metric);
		

		name = "consumeRateCount";
		metric = Metrics.newGauge(ZoiePerf.class, name,
				new GaugeMetric<Long>() {

					long prevCount = 0L;
					long prevTime = 0L;

					@Override
					public Long value() {
						long newTime = System.currentTimeMillis();
						
						long newCount =  dataProvider.getEventCount();

						long timeDelta = newTime - prevTime;
						long countDelta = newCount - prevCount;

						prevTime = newTime;
						prevCount = newCount;

						if (timeDelta == 0)
							return 0L;
						return countDelta * 1000 / timeDelta;
					}

				});


		monitoredMetrics.put(name, metric);
		

		name = "consumeRateMB";
		metric = Metrics.newGauge(ZoiePerf.class, name,
				new GaugeMetric<Long>() {
					long prevMB = 0L;
					long prevTime = 0L;

					@Override
					public Long value() {
						long newTime = System.currentTimeMillis();
						ZoiePerfVersion ver = ZoiePerfVersion.fromString(testHandler.consumer.getVersion());
						long newMB = ver.offsetVersion;

						long timeDelta = newTime - prevTime;
						long mbdelta = newMB - prevMB;

						prevTime = newTime;
						prevMB = newMB;

						if (timeDelta == 0)
							return 0L;
						return mbdelta * 1000 / timeDelta;
					}

				});

		monitoredMetrics.put(name, metric);
		
		name = "indexLatency";
		metric = Metrics.newGauge(ZoiePerf.class, name,
				new GaugeMetric<Long>() {

					long prevCount = 0L;
					long prevTime = start;

					@Override
					public Long value() {
						long newTime = System.currentTimeMillis();
						long newCount =  dataProvider.getEventCount();

						long timeDelta = newTime - prevTime;
						long countDelta = newCount - prevCount;
						
						String currentReaderVersion = testHandler.readerFactory.getCurrentReaderVersion();
						long readerMarker = ZoiePerfVersion.fromString(currentReaderVersion).countVersion;

						prevTime = newTime;
						prevCount = newCount;
						
						long countsBehind = newCount - readerMarker;

/*
						System.out.println("counts behind: "+countsBehind);
						System.out.println("new count: "+newCount);
						System.out.println("reader marker: "+readerMarker);
						System.out.println("time delta: "+timeDelta);
						System.out.println("count delta: "+countDelta);
						*/
						if (countDelta > 0){
						  return timeDelta*countsBehind/countDelta;
						}
						else{
						  return 0L;
						}
						
						
					}

				});


		monitoredMetrics.put(name, metric);
		//ConsoleReporter consoleReporter = new ConsoleReporter(System.out);
		//consoleReporter.start(5, TimeUnit.SECONDS);

		//JmxReporter jmxReporter = new JmxReporter(Metrics.defaultRegistry());
		
		File csvOut = new File("csvout");
		csvOut.mkdirs();
		CsvReporter csvReporter = new CsvReporter(csvOut,Metrics.defaultRegistry());
		
		int updateInterval = conf.getInt("perf.update.intervalSec", 2);
		csvReporter.start(updateInterval, TimeUnit.SECONDS);

		dataProvider.setDataConsumer(testHandler.consumer);
		long maxEventsPerMin = conf.getLong("perf.maxEventsPerMin");

		dataProvider.setMaxEventsPerMinute(maxEventsPerMin);

		int numThreads = conf.getInt("perf.query.threads", 10);
		
		SearchThread[] searchThreads = null;
		
		
		if (doSearchTest){
		  searchThreads = new SearchThread[numThreads];
		  for (int i=0;i<numThreads;++i){
			searchThreads[i] = new SearchThread();
		  }
		}
		else{
		  searchThreads = new SearchThread[0];
		}
		
		dataProvider.start();
		
		for (int i=0;i<searchThreads.length;++i){
			searchThreads[i].start();
		}

		ZoiePerfVersion perfVersion = ZoiePerfVersion.fromString(testHandler.consumer.getVersion());
		while ((dataAmount = perfVersion.offsetVersion) < maxSize) {
			Thread.sleep(500);
			perfVersion = ZoiePerfVersion.fromString(testHandler.consumer.getVersion());
		}

		dataProvider.stop();
		testHandler.consumer.stop();

		long end = System.currentTimeMillis();
		
		for (int i=0;i<searchThreads.length;++i){
			searchThreads[i].terminate();
		}
		
		for (int i=0;i<searchThreads.length;++i){
			searchThreads[i].join();
		}

		//consoleReporter.shutdown();
		//jmxReporter.shutdown();
		
		csvReporter.shutdown();

		System.out.println("Test duration: " + (end - start) + " ms");

		System.out.println("Amount of data consumed: " + dataAmount);
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		File confFile;
		try {
			confFile = new File(new File(args[0]), "perf.properties");
		} catch (Exception e) {
			confFile = new File(new File("conf"), "perf.properties");
		}

		if (!confFile.exists()) {
			throw new ConfigurationException("configuration file: "
					+ confFile.getAbsolutePath() + " does not exist.");
		}
		

		Configuration conf = new PropertiesConfiguration();
		((PropertiesConfiguration) conf).setDelimiterParsingDisabled(true);
		((PropertiesConfiguration) conf).load(confFile);


		
		Server server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        connector.setPort(18888);
        server.addConnector(connector);
 
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setWelcomeFiles(new String[]{ "index.html" });
 
        resourceHandler.setResourceBase("./");
        
        ResourceHandler csvDataHandler = new ResourceHandler();
        csvDataHandler.setResourceBase("./csvOut");
        

        server.setHandler(resourceHandler);
        
        final Context context = new Context(server, "/servlets", Context.ALL);
        context.addServlet(new ServletHolder(new ZoiePerfServlet(new File("csvout"))), "/zoie-perf/*");
        
        server.start();
        
        runPerf(conf);
		
        server.join();
        
	}
}