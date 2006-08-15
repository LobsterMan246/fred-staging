/*
 * Freenet 0.7 node.
 * 
 * Designed primarily for darknet operation, but should also be usable
 * in open mode eventually.
 */
package freenet.node;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.zip.DeflaterOutputStream;

import org.tanukisoftware.wrapper.WrapperManager;

import com.sleepycat.je.DatabaseException;

import freenet.client.FetcherContext;
import freenet.config.Config;
import freenet.config.IntCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.LongCallback;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.Global;
import freenet.crypt.RandomSource;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.UdpSocketManager;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.ClientCHK;
import freenet.keys.ClientCHKBlock;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.keys.ClientSSKBlock;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.node.updater.NodeUpdater;
import freenet.node.useralerts.BuildOldAgeUserAlert;
import freenet.node.useralerts.ExtOldAgeUserAlert;
import freenet.node.useralerts.MeaningfulNodeNameUserAlert;
import freenet.node.useralerts.N2NTMUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.PluginManager;
import freenet.store.BerkeleyDBFreenetStore;
import freenet.store.FreenetStore;
import freenet.store.KeyCollisionException;
import freenet.support.Base64;
import freenet.support.DoubleTokenBucket;
import freenet.support.Fields;
import freenet.support.FileLoggerHook;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.ImmutableByteArrayWrapper;
import freenet.support.LRUHashtable;
import freenet.support.LRUQueue;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.TokenBucket;
import freenet.support.math.RunningAverage;
import freenet.support.math.TimeDecayingRunningAverage;

/**
 * @author amphibian
 */
public class Node {

	static class NodeBindtoCallback implements StringCallback {
		
		final Node node;
		
		NodeBindtoCallback(Node n) {
			this.node = n;
		}
		
		public String get() {
			if(node.getBindTo()!=null)
				return node.getBindTo();
			else
				return "0.0.0.0";
		}
		
		public void set(String val) throws InvalidConfigValueException {
			if(val.equals(get())) return;
			throw new InvalidConfigValueException("Cannot be updated on the fly");
		}
	}
	
	private static MeaningfulNodeNameUserAlert nodeNameUserAlert;
	private static BuildOldAgeUserAlert buildOldAgeUserAlert;
	
	public class NodeNameCallback implements StringCallback{
			Node node;
		
			NodeNameCallback(Node n) {
				node=n;
			}
			public String get() {
				if(myName.startsWith("Node created around")|| myName.equals("MyFirstFreenetNode")){
					clientCore.alerts.register(nodeNameUserAlert);
				}else{
					clientCore.alerts.unregister(nodeNameUserAlert);
				}
				return myName;
			}

			public void set(String val) throws InvalidConfigValueException {
				myName = val;
				if(myName.startsWith("Node created around")|| myName.equals("MyFirstFreenetNode")){
					clientCore.alerts.register(nodeNameUserAlert);
				}else{
					clientCore.alerts.unregister(nodeNameUserAlert);
				}
			}
	}
	
	/** Config object for the whole node. */
	public final Config config;
	
	// Static stuff related to logger
	
	/** Directory to log to */
	static File logDir;
	/** Maximum size of gzipped logfiles */
	static long maxLogSize;
	/** Log config handler */
	public static LoggingConfigHandler logConfigHandler;
	
	static int aggressiveGCModificator = 250;
	
	/** If true, local requests and inserts aren't cached.
	 * This opens up a glaring vulnerability; connected nodes
	 * can then probe the store, and if the node doesn't have the
	 * content, they know for sure that it was a local request.
	 * HOWEVER, if we don't do this, then a non-full seized 
	 * datastore will contain everything requested by the user...
	 * Also, remote probing is possible.
	 * 
	 * So it may be useful on some darknets, and is useful for 
	 * debugging, but in general should be off on opennet and 
	 * most darknets.
	 */
	public static final boolean DONT_CACHE_LOCAL_REQUESTS = true;
	public static final int PACKETS_IN_BLOCK = 32;
	public static final int PACKET_SIZE = 1024;
	public static final double DECREMENT_AT_MIN_PROB = 0.25;
	public static final double DECREMENT_AT_MAX_PROB = 0.5;
	// Send keepalives every 2.5-5.0 seconds
	public static final int KEEPALIVE_INTERVAL = 2500;
	// If no activity for 30 seconds, node is dead
	public static final int MAX_PEER_INACTIVITY = 60000;
	/** Time after which a handshake is assumed to have failed. */
	public static final int HANDSHAKE_TIMEOUT = 5000;
	// Inter-handshake time must be at least 2x handshake timeout
	public static final int MIN_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT*2; // 10-15 secs
	public static final int RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS = HANDSHAKE_TIMEOUT;
	public static final int MIN_TIME_BETWEEN_VERSION_PROBES = HANDSHAKE_TIMEOUT*4;
	public static final int RANDOMIZED_TIME_BETWEEN_VERSION_PROBES = HANDSHAKE_TIMEOUT*2; // 20-30 secs
	public static final int MIN_TIME_BETWEEN_VERSION_SENDS = HANDSHAKE_TIMEOUT*4;
	public static final int RANDOMIZED_TIME_BETWEEN_VERSION_SENDS = HANDSHAKE_TIMEOUT*2; // 20-30 secs
	public static final int MIN_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS = HANDSHAKE_TIMEOUT*24; // 2-5 minutes
	public static final int RANDOMIZED_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS = HANDSHAKE_TIMEOUT*36;
	public static final int MIN_BURSTING_HANDSHAKE_BURST_SIZE = 1; // 1-4 handshake sends per burst
	public static final int RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE = 3;
	// If we don't receive any packets at all in this period, from any node, tell the user
	public static final long ALARM_TIME = 60*1000;
	/** Sub-max ping time. If ping is greater than this, we reject some requests. */
	public static final long SUB_MAX_PING_TIME = 700;
	/** Maximum overall average ping time. If ping is greater than this,
	 * we reject all requests. */
	public static final long MAX_PING_TIME = 1500;
	/** Maximum throttled packet delay. If the throttled packet delay is greater
	 * than this, reject all packets. */
	public static final long MAX_THROTTLE_DELAY = 2000;
	/** If the throttled packet delay is less than this, reject no packets; if it's
	 * between the two, reject some packets. */
	public static final long SUB_MAX_THROTTLE_DELAY = 1000;
	/** How high can bwlimitDelayTime be before we alert (in milliseconds)*/
	public static final long MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD = MAX_THROTTLE_DELAY*2;
	/** How high can nodeAveragePingTime be before we alert (in milliseconds)*/
	public static final long MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD = MAX_PING_TIME*2;
	/** How long we're over the bwlimitDelayTime threshold before we alert (in milliseconds)*/
	public static final long MAX_BWLIMIT_DELAY_TIME_ALERT_DELAY = 10*60*1000;  // 10 minutes
	/** How long we're over the nodeAveragePingTime threshold before we alert (in milliseconds)*/
	public static final long MAX_NODE_AVERAGE_PING_TIME_ALERT_DELAY = 10*60*1000;  // 10 minutes
	
	/** Accept one request every 10 seconds regardless, to ensure we update the
	 * block send time.
	 */
	public static final int MAX_INTERREQUEST_TIME = 10*1000;

	// 900ms
	static final int MIN_INTERVAL_BETWEEN_INCOMING_SWAP_REQUESTS = 900;
	public static final int SYMMETRIC_KEY_LENGTH = 32; // 256 bits - note that this isn't used everywhere to determine it
	/** Minimum space for zipped logfiles on testnet */
	static final long TESTNET_MIN_MAX_ZIPPED_LOGFILES = 512*1024*1024;
	static final String TESTNET_MIN_MAX_ZIPPED_LOGFILES_STRING = "512M";
	
	// FIXME: abstract out address stuff? Possibly to something like NodeReference?
	final int portNumber;

	/** Datastore directory */
	private final File storeDir;

	/** The number of bytes per key total in all the different datastores. All the datastores
	 * are always the same size in number of keys. */
	static final int sizePerKey = CHKBlock.DATA_LENGTH + CHKBlock.TOTAL_HEADERS_LENGTH +
		DSAPublicKey.PADDED_SIZE + SSKBlock.DATA_LENGTH + SSKBlock.TOTAL_HEADERS_LENGTH;
	
	/** The maximum number of keys stored in each of the datastores, cache and store combined. */
	private long maxTotalKeys;
	private long maxCacheKeys;
	private long maxStoreKeys;
	
	/* These are private because must be protected by synchronized(this) */
	/** The CHK datastore. Long term storage; data should only be inserted here if
	 * this node is the closest location on the chain so far, and it is on an 
	 * insert (because inserts will always reach the most specialized node; if we
	 * allow requests to store here, then we get pollution by inserts for keys not
	 * close to our specialization). These conclusions derived from Oskar's simulations. */
	private final FreenetStore chkDatastore;
	/** The SSK datastore. See description for chkDatastore. */
	private final FreenetStore sskDatastore;
	/** The store of DSAPublicKeys (by hash). See description for chkDatastore. */
	private final FreenetStore pubKeyDatastore;
	/** The CHK datacache. Short term cache which stores everything that passes
	 * through this node. */
	private final FreenetStore chkDatacache;
	/** The SSK datacache. Short term cache which stores everything that passes
	 * through this node. */
	private final FreenetStore sskDatacache;
	/** The public key datacache (by hash). Short term cache which stores 
	 * everything that passes through this node. */
	private final FreenetStore pubKeyDatacache;
	/** RequestSender's currently running, by KeyHTLPair */
	private final HashMap requestSenders;
	/** RequestSender's currently transferring, by key */
	private final HashMap transferringRequestSenders;
	/** CHKInsertSender's currently running, by KeyHTLPair */
	private final HashMap insertSenders;
	/** My crypto group */
	private DSAGroup myCryptoGroup;
	/** My private key */
	private DSAPrivateKey myPrivKey;
	/** My public key */
	private DSAPublicKey myPubKey;
	/** Memory Checker thread */
	private final Thread myMemoryChecker;
	/** My ARK SSK private key */
	InsertableClientSSK myARK;
	/** My ARK sequence number */
	long myARKNumber;
	/** FetcherContext for ARKs */
	public final FetcherContext arkFetcherContext;
	/** ARKFetcher's currently running, by identity */
	private final HashMap arkFetchers;
	/** Next time to log the PeerNode status summary */
	private long nextPeerNodeStatusLogTime = -1;
	/** PeerNode status summary log interval (milliseconds) */
	private static final long peerNodeStatusLogInterval = 1000;
	/** PeerNode statuses, by status */
	private final HashMap peerNodeStatuses;
	/** PeerNode routing backoff reasons, by reason */
	private final HashMap peerNodeRoutingBackoffReasons;
	/** Next time to update oldestNeverConnectedPeerAge */
	private long nextOldestNeverConnectedPeerAgeUpdateTime = -1;
	/** oldestNeverConnectedPeerAge update interval (milliseconds) */
	private static final long oldestNeverConnectedPeerAgeUpdateInterval = 5000;
	/** age of oldest never connected peer (milliseconds) */
	private long oldestNeverConnectedPeerAge;
	/** Next time to start a ready ARKFetcher */
	private long nextReadyARKFetcherStartTime = -1;
	/** Ready ARKFetcher start interval (milliseconds) */
	private static final long readyARKFetcherStartInterval = 1000;
	/** Next time to update PeerManagerUserAlert stats */
	private long nextPeerManagerUserAlertStatsUpdateTime = -1;
	/** PeerManagerUserAlert stats update interval (milliseconds) */
	private static final long peerManagerUserAlertStatsUpdateInterval = 1000;  // 1 second
	/** first time bwlimitDelay was over PeerManagerUserAlert threshold */
	private long firstBwlimitDelayTimeThresholdBreak ;
	/** first time nodeAveragePing was over PeerManagerUserAlert threshold */
	private long firstNodeAveragePingTimeThresholdBreak;
	/** bwlimitDelay PeerManagerUserAlert should happen if true */
	public boolean bwlimitDelayAlertRelevant;
	/** nodeAveragePing PeerManagerUserAlert should happen if true */
	public boolean nodeAveragePingAlertRelevant;
	/** Average proportion of requests rejected immediately due to overload */
	public final TimeDecayingRunningAverage pInstantRejectIncoming;
	/** IP detector */
	public final NodeIPDetector ipDetector;
	
	private final HashSet runningUIDs;
	
	byte[] myIdentity; // FIXME: simple identity block; should be unique
	/** Hash of identity. Used as setup key. */
	byte[] identityHash;
	/** Hash of hash of identity i.e. hash of setup key. */
	byte[] identityHashHash; 
	String myName;
	final LocationManager lm;
	final PeerManager peers; // my peers
	final ARKFetchManager arkFetchManager; // ready ARK Fetchers
	/** Directory to put node, peers, etc into */
	final File nodeDir;
	/** Directory to put extra peer data into */
	final File extraPeerDataDir;
	public final RandomSource random; // strong RNG
	final UdpSocketManager usm;
	final FNPPacketMangler packetMangler;
	final DNSRequester dnsr;
	public final PacketSender ps;
	final NodeDispatcher dispatcher;
	final NodePinger nodePinger;
	static final int MAX_CACHED_KEYS = 1000;
	final LRUHashtable cachedPubKeys;
	final boolean testnetEnabled;
	final TestnetHandler testnetHandler;
	final StaticSwapRequestInterval swapInterval;
	public final DoubleTokenBucket outputThrottle;
	final TokenBucket requestOutputThrottle;
	final TokenBucket requestInputThrottle;
	private boolean inputLimitDefault;
	static short MAX_HTL = 10;
	static final int EXIT_STORE_FILE_NOT_FOUND = 1;
	static final int EXIT_STORE_IOEXCEPTION = 2;
	static final int EXIT_STORE_OTHER = 3;
	static final int EXIT_USM_DIED = 4;
	public static final int EXIT_YARROW_INIT_FAILED = 5;
	static final int EXIT_TEMP_INIT_ERROR = 6;
	static final int EXIT_TESTNET_FAILED = 7;
	public static final int EXIT_MAIN_LOOP_LOST = 8;
	public static final int EXIT_COULD_NOT_BIND_USM = 9;
	static final int EXIT_IMPOSSIBLE_USM_PORT = 10;
	static final int EXIT_NO_AVAILABLE_UDP_PORTS = 11;
	public static final int EXIT_TESTNET_DISABLED_NOT_SUPPORTED = 12;
	static final int EXIT_INVALID_STORE_SIZE = 13;
	static final int EXIT_BAD_DOWNLOADS_DIR = 14;
	static final int EXIT_BAD_NODE_DIR = 15;
	static final int EXIT_BAD_TEMP_DIR = 16;
	static final int EXIT_COULD_NOT_START_FCP = 17;
	static final int EXIT_COULD_NOT_START_FPROXY = 18;
	static final int EXIT_COULD_NOT_START_TMCI = 19;
	public static final int EXIT_DATABASE_REQUIRES_RESTART = 20;
	public static final int EXIT_COULD_NOT_START_UPDATER = 21;
	static final int EXIT_EXTRA_PEER_DATA_DIR = 22;
	static final int EXIT_THROTTLE_FILE_ERROR = 23;
	public static final int PEER_NODE_STATUS_CONNECTED = 1;
	public static final int PEER_NODE_STATUS_ROUTING_BACKED_OFF = 2;
	public static final int PEER_NODE_STATUS_TOO_NEW = 3;
	public static final int PEER_NODE_STATUS_TOO_OLD = 4;
	public static final int PEER_NODE_STATUS_DISCONNECTED = 5;
	public static final int PEER_NODE_STATUS_NEVER_CONNECTED = 6;
	public static final int PEER_NODE_STATUS_DISABLED = 7;
	public static final int PEER_NODE_STATUS_BURSTING = 8;
	public static final int PEER_NODE_STATUS_LISTENING = 9;
	public static final int PEER_NODE_STATUS_LISTEN_ONLY = 10;
	public static final int N2N_TEXT_MESSAGE_TYPE_USERALERT = 1;
	public static final int EXTRA_PEER_DATA_TYPE_N2NTM = 1;
	
	public final long bootID;
	public final long startupTime;
	
	public final NodeClientCore clientCore;
	final String bindto;
	/** Average delay caused by throttling for sending a packet */
	final TimeDecayingRunningAverage throttledPacketSendAverage;
	
	// Stats
	final TimeDecayingRunningAverage remoteChkFetchBytesSentAverage;
	final TimeDecayingRunningAverage remoteSskFetchBytesSentAverage;
	final TimeDecayingRunningAverage remoteChkInsertBytesSentAverage;
	final TimeDecayingRunningAverage remoteSskInsertBytesSentAverage;
	final TimeDecayingRunningAverage remoteChkFetchBytesReceivedAverage;
	final TimeDecayingRunningAverage remoteSskFetchBytesReceivedAverage;
	final TimeDecayingRunningAverage remoteChkInsertBytesReceivedAverage;
	final TimeDecayingRunningAverage remoteSskInsertBytesReceivedAverage;
	final TimeDecayingRunningAverage localChkFetchBytesSentAverage;
	final TimeDecayingRunningAverage localSskFetchBytesSentAverage;
	final TimeDecayingRunningAverage localChkInsertBytesSentAverage;
	final TimeDecayingRunningAverage localSskInsertBytesSentAverage;
	final TimeDecayingRunningAverage localChkFetchBytesReceivedAverage;
	final TimeDecayingRunningAverage localSskFetchBytesReceivedAverage;
	final TimeDecayingRunningAverage localChkInsertBytesReceivedAverage;
	final TimeDecayingRunningAverage localSskInsertBytesReceivedAverage;
	File persistTarget; 
	File persistTemp;
	
	// The version we were before we restarted.
	public int lastVersion;
	
	/** NodeUpdater **/
	public NodeUpdater nodeUpdater;
	
	// Things that's needed to keep track of
	public final PluginManager pluginManager;
	public freenet.plugin.PluginManager pluginManager2;
	
	// Helpers
	public final InetAddress localhostAddress;
	public final FreenetInetAddress fLocalhostAddress;

	private boolean wasTestnet;

	// The node starter
	private static NodeStarter nodeStarter;
	
	// The watchdog will be silenced until it's true
	private boolean hasStarted;
	
	// Debugging stuff
	private static final boolean USE_RAM_PUBKEYS_CACHE = true;
	
	// various metrics
	public RunningAverage missRoutingDistance = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0);
	public RunningAverage backedoffPercent = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0);
	protected final ThrottlePersister throttlePersister;
	
	/**
	 * Read all storable settings (identity etc) from the node file.
	 * @param filename The name of the file to read from.
	 */
	private void readNodeFile(String filename, RandomSource r) throws IOException {
		// REDFLAG: Any way to share this code with NodePeer?
		FileInputStream fis = new FileInputStream(filename);
		InputStreamReader isr = new InputStreamReader(fis);
		BufferedReader br = new BufferedReader(isr);
		SimpleFieldSet fs = new SimpleFieldSet(br);
		br.close();
		// Read contents
		String[] udp = fs.getAll("physical.udp");
		if((udp != null) && (udp.length > 0)) {
			for(int i=0;i<udp.length;i++) {
				// Just keep the first one with the correct port number.
				Peer p;
				try {
					p = new Peer(udp[i], false);
				} catch (PeerParseException e) {
					IOException e1 = new IOException();
					e1.initCause(e);
					throw e1;
				}
				if(p.getPort() == portNumber) {
					// DNSRequester doesn't deal with our own node
					ipDetector.setOldIPAddress(p.getFreenetAddress());
					break;
				}
			}
		}
		String identity = fs.get("identity");
		if(identity == null)
			throw new IOException();
		try {
			myIdentity = Base64.decode(identity);
		} catch (IllegalBase64Exception e2) {
			throw new IOException();
		}
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
		identityHash = md.digest(myIdentity);
		identityHashHash = md.digest(identityHash);
		String loc = fs.get("location");
		Location l;
		try {
			l = new Location(loc);
		} catch (FSParseException e) {
			IOException e1 = new IOException();
			e1.initCause(e);
			throw e1;
		}
		lm.setLocation(l);
		myName = fs.get("myName");
		if(myName == null) {
			myName = newName();
		}
		
		String verString = fs.get("version");
		if(verString == null) {
			Logger.error(this, "No version!");
			System.err.println("No version!");
		} else {
			lastVersion = Version.getArbitraryBuildNumber(verString);
		}

		// FIXME: Back compatibility; REMOVE !!
		try {
			this.myCryptoGroup = DSAGroup.create(fs.subset("dsaGroup"));
			this.myPrivKey = DSAPrivateKey.create(fs.subset("dsaPrivKey"), myCryptoGroup);
			this.myPubKey = DSAPublicKey.create(fs.subset("dsaPubKey"), myCryptoGroup);
		} catch (NullPointerException e) {
			Logger.minor(this, "Caught "+e, e);
			this.myCryptoGroup = Global.DSAgroupBigA;
			this.myPrivKey = new DSAPrivateKey(myCryptoGroup, r);
			this.myPubKey = new DSAPublicKey(myCryptoGroup, myPrivKey);
		} catch (IllegalBase64Exception e) {
			Logger.minor(this, "Caught "+e, e);
			this.myCryptoGroup = Global.DSAgroupBigA;
			this.myPrivKey = new DSAPrivateKey(myCryptoGroup, r);
			this.myPubKey = new DSAPublicKey(myCryptoGroup, myPrivKey);
		}
		InsertableClientSSK ark = null;
		String s = fs.get("ark.number");
		
		String privARK = fs.get("ark.privURI");
		try {
			if(privARK != null) {
				FreenetURI uri = new FreenetURI(privARK);
				ark = InsertableClientSSK.create(uri);
				if(s == null) {
					ark = null;
				} else {
					try {
						myARKNumber = Long.parseLong(s);
					} catch (NumberFormatException e) {
						myARKNumber = 0;
						ark = null;
					}
				}
			}
		} catch (MalformedURLException e) {
			Logger.minor(this, "Caught "+e, e);
			ark = null;
		}
		if(ark == null) {
			ark = InsertableClientSSK.createRandom(r, "ark");
			myARKNumber = 0;
		}
		this.myARK = ark;
		wasTestnet = Fields.stringToBool(fs.get("testnet"), false);
	}

	private String newName() {
		return "Node created around "+System.currentTimeMillis();
	}

	public void writeNodeFile() {
		writeNodeFile(new File(nodeDir, "node-"+portNumber), new File(nodeDir, "node-"+portNumber+".bak"));
	}
	
	private void writeNodeFile(File orig, File backup) {
		SimpleFieldSet fs = exportPrivateFieldSet();
		
		if(orig.exists()) backup.delete();
		
		OutputStreamWriter osr = null;
		try {
			FileOutputStream fos = new FileOutputStream(backup);
			osr = new OutputStreamWriter(fos);
			fs.writeTo(osr);
			osr.close();
			if(!backup.renameTo(orig)) {
				orig.delete();
				if(!backup.renameTo(orig)) {
					Logger.error(this, "Could not rename new node file "+backup+" to "+orig);
				}
			}
		} catch (IOException e) {
			if(osr != null) {
				try {
					osr.close();
				} catch (IOException e1) {
					Logger.error(this, "Cannot close "+backup+": "+e1, e1);
				}
			}
		}
	}

	private void initNodeFileSettings(RandomSource r) {
		Logger.normal(this, "Creating new node file from scratch");
		// Don't need to set portNumber
		// FIXME use a real IP!
		myIdentity = new byte[32];
		r.nextBytes(myIdentity);
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
		identityHash = md.digest(myIdentity);
		identityHashHash = md.digest(identityHash);
		myName = newName();
		this.myCryptoGroup = Global.DSAgroupBigA;
		this.myPrivKey = new DSAPrivateKey(myCryptoGroup, r);
		this.myPubKey = new DSAPublicKey(myCryptoGroup, myPrivKey);
		myARK = InsertableClientSSK.createRandom(r, "ark");
		myARKNumber = 0;
	}

	/**
	 * Read the config file from the arguments.
	 * Then create a node.
	 * Anything that needs static init should ideally be in here.
	 */
	public static void main(String[] args) throws IOException {
		NodeStarter.main(args);
	}
	
	static class NodeInitException extends Exception {
		// One of the exit codes from above
		public final int exitCode;
	private static final long serialVersionUID = -1;
		
		NodeInitException(int exitCode, String msg) {
			super(msg+" ("+exitCode+")");
			this.exitCode = exitCode;
		}
	}
	
	Node(Config config, RandomSource random, LoggingConfigHandler lc) throws NodeInitException{
		this(config, random, lc, null);
	}
	
	public boolean isUsingWrapper(){
		if(nodeStarter!=null && WrapperManager.isControlledByNativeWrapper())
			return true;
		else 
			return false;
	}
	
	public NodeStarter getNodeStarter(){
		return nodeStarter;
	}
	
	/**
	 * Create a Node from a Config object.
	 * @param config The Config object for this node.
	 * @param random The random number generator for this node. Passed in because we may want
	 * to use a non-secure RNG for e.g. one-JVM live-code simulations. Should be a Yarrow in
	 * a production node.
	 * @param the loggingHandler
	 * @throws NodeInitException If the node initialization fails.
	 */
	 Node(Config config, RandomSource random, LoggingConfigHandler lc, NodeStarter ns) throws NodeInitException {
		// Easy stuff
		Logger.normal(this, "Initializing Node using SVN r"+Version.cvsRevision+" and freenet-ext r"+NodeStarter.extRevisionNumber);
		System.out.println("Initializing Node using SVN r"+Version.cvsRevision+" and freenet-ext r"+NodeStarter.extRevisionNumber);
		pInstantRejectIncoming = new TimeDecayingRunningAverage(0, 60000, 0.0, 1.0);
	  	nodeStarter=ns;
		if(logConfigHandler != lc)
			logConfigHandler=lc;
		startupTime = System.currentTimeMillis();
		nodeNameUserAlert = new MeaningfulNodeNameUserAlert(this);
		recentlyCompletedIDs = new LRUQueue();
		this.config = config;
		this.random = random;
		cachedPubKeys = new LRUHashtable();
		lm = new LocationManager(random);
		try {
			localhostAddress = InetAddress.getByName("127.0.0.1");
		} catch (UnknownHostException e3) {
			// Does not do a reverse lookup, so this is impossible
			throw new Error(e3);
		}
		fLocalhostAddress = new FreenetInetAddress(localhostAddress);
		requestSenders = new HashMap();
		transferringRequestSenders = new HashMap();
		insertSenders = new HashMap();
		arkFetchers = new HashMap();
		peerNodeStatuses = new HashMap();
		peerNodeRoutingBackoffReasons = new HashMap();
		runningUIDs = new HashSet();
		dnsr = new DNSRequester(this);
		ps = new PacketSender(this);
		// FIXME maybe these should persist? They need to be private though, so after the node/peers split. (bug 51).
		decrementAtMax = random.nextDouble() <= DECREMENT_AT_MAX_PROB;
		decrementAtMin = random.nextDouble() <= DECREMENT_AT_MIN_PROB;
		bootID = random.nextLong();
		throttledPacketSendAverage =
			new TimeDecayingRunningAverage(1, 10*60*1000 /* should be significantly longer than a typical transfer */, 0, Long.MAX_VALUE);
		
		buildOldAgeUserAlert = new BuildOldAgeUserAlert();

		int sortOrder = 0;
		// Setup node-specific configuration
		SubConfig nodeConfig = new SubConfig("node", config);
		
		
		nodeConfig.register("aggressiveGC", aggressiveGCModificator, sortOrder++, true, "AggressiveGC modificator", "Enables the user to tweak the time in between GC and forced finalization. SHOULD NOT BE CHANGED unless you know what you're doing! -1 means : disable forced call to System.gc() and System.runFinalization()",
				new IntCallback() {
					public int get() {
						return aggressiveGCModificator;
					}
					public void set(int val) throws InvalidConfigValueException {
						if(val == get()) return;
						Logger.normal(this, "Changing aggressiveGCModificator to "+val);
						aggressiveGCModificator = val;
					}
		});
		
		//Memory Checking thread
		// TODO: proper config. callbacks : maybe we shoudln't start the thread at all if it's not worthy
    	this.myMemoryChecker = new Thread(new MemoryChecker(), "Memory checker");
    	this.myMemoryChecker.setPriority(Thread.MAX_PRIORITY);
    	this.myMemoryChecker.setDaemon(true);

    	// FIXME maybe these configs should actually be under a node.ip subconfig?
    	ipDetector = new NodeIPDetector(this);
    	sortOrder = ipDetector.registerConfigs(nodeConfig, sortOrder);
    	
		// Determine where to bind to
		

		
		nodeConfig.register("bindTo", "0.0.0.0", sortOrder++, true, "IP address to bind to", "IP address to bind to",
				new NodeBindtoCallback(this));
		
		this.bindto = nodeConfig.getString("bindTo");
		
		// Determine the port number
		
		nodeConfig.register("listenPort", -1 /* means random */, sortOrder++, true, "FNP port number (UDP)", "UDP port for node-to-node communications (Freenet Node Protocol)",
				new IntCallback() {
					public int get() {
						return portNumber;
					}
					public void set(int val) throws InvalidConfigValueException {
						// FIXME implement on the fly listenPort changing
						// Note that this sort of thing should be the exception rather than the rule!!!!
						String msg = "Switching listenPort on the fly not yet supported!";
						Logger.error(this, msg);
						throw new InvalidConfigValueException(msg);
					}
		});
		
		int port=-1;
		try{
			port=nodeConfig.getInt("listenPort");
		}catch (Exception e){
			Logger.error(this, "Caught "+e, e);
			System.err.println(e);
			e.printStackTrace();
			port=-1;
		}
		
		UdpSocketManager u = null;
		
		if(port > 65535) {
			throw new NodeInitException(EXIT_IMPOSSIBLE_USM_PORT, "Impossible port number: "+port);
		} else if(port == -1) {
			// Pick a random port
			for(int i=0;i<200000;i++) {
				int portNo = 1024 + random.nextInt(65535-1024);
				try {
					u = new UdpSocketManager(portNo, InetAddress.getByName(bindto), this);
					port = u.getPortNumber();
					break;
				} catch (Exception e) {
					Logger.normal(this, "Could not use port: "+bindto+":"+portNo+": "+e, e);
					System.err.println("Could not use port: "+bindto+":"+portNo+": "+e);
					e.printStackTrace();
					continue;
				}
			}
			if(u == null)
				throw new NodeInitException(EXIT_NO_AVAILABLE_UDP_PORTS, "Could not find an available UDP port number for FNP (none specified)");
		} else {
			try {
				u = new UdpSocketManager(port, InetAddress.getByName(bindto), this);
			} catch (Exception e) {
				throw new NodeInitException(EXIT_IMPOSSIBLE_USM_PORT, "Could not bind to port: "+port+" (node already running?)");
			}
		}
		usm = u;
		
		Logger.normal(this, "FNP port created on "+bindto+":"+port);
		System.out.println("FNP port created on "+bindto+":"+port);
		portNumber = port;
		
		Logger.normal(Node.class, "Creating node...");

		// Bandwidth limit

		// FIXME These should not be static !!!! Need a context object for BT for bwlimiting.
		// See bug 77
		nodeConfig.register("outputBandwidthLimit", "15K", sortOrder++, false, 
				"Output bandwidth limit (bytes per second)", "Hard output bandwidth limit (bytes/sec); the node should almost never exceed this", 
				new IntCallback() {
					public int get() {
						//return BlockTransmitter.getHardBandwidthLimit();
						return (int) ((1000L * 1000L * 1000L) / outputThrottle.getNanosPerTick());
					}
					public void set(int obwLimit) throws InvalidConfigValueException {
						if(obwLimit <= 0) throw new InvalidConfigValueException("Bandwidth limit must be positive");
						outputThrottle.changeNanosAndBucketSizes((1000L * 1000L * 1000L) / obwLimit, obwLimit/2, (obwLimit * 2) / 5);
						obwLimit = (obwLimit * 4) / 5; // fudge factor; take into account non-request activity
						requestOutputThrottle.changeNanosAndBucketSize((1000L*1000L*1000L) /  obwLimit, Math.max(obwLimit*60, 32768*20));
						if(inputLimitDefault) {
							int ibwLimit = obwLimit * 4;
							requestInputThrottle.changeNanosAndBucketSize((1000L*1000L*1000L) /  ibwLimit, Math.max(ibwLimit*60, 32768*20));
						}
					}
		});
		
		int obwLimit = nodeConfig.getInt("outputBandwidthLimit");
		outputThrottle = new DoubleTokenBucket(obwLimit/2, (1000L*1000L*1000L) /  obwLimit, obwLimit, (obwLimit * 2) / 5);
		obwLimit = (obwLimit * 4) / 5;  // fudge factor; take into account non-request activity
		requestOutputThrottle = 
			new TokenBucket(Math.max(obwLimit*60, 32768*20), (1000L*1000L*1000L) /  obwLimit, 0);
		
		nodeConfig.register("inputBandwidthLimit", "-1", sortOrder++, false,
				"Input bandwidth limit (bytes per second)", "Input bandwidth limit (bytes/sec); the node will try not to exceed this; -1 = 4x set outputBandwidthLimit",
				new IntCallback() {
					public int get() {
						if(inputLimitDefault) return -1;
						return (((int) ((1000L * 1000L * 1000L) / requestInputThrottle.getNanosPerTick())) * 5) / 4;
					}
					public void set(int ibwLimit) throws InvalidConfigValueException {
						if(ibwLimit == -1) {
							inputLimitDefault = true;
							ibwLimit = (int) ((1000L * 1000L * 1000L) / outputThrottle.getNanosPerTick()) * 4;
						} else {
							ibwLimit = ibwLimit * 4 / 5; // fudge factor; take into account non-request activity
						}
						if(ibwLimit <= 0) throw new InvalidConfigValueException("Bandwidth limit must be positive or -1");
						requestInputThrottle.changeNanosAndBucketSize((1000L*1000L*1000L) /  ibwLimit, Math.max(ibwLimit*60, 32768*20));
					}
		});
		
		int ibwLimit = nodeConfig.getInt("inputBandwidthLimit");
		if(ibwLimit == -1) {
			inputLimitDefault = true;
			ibwLimit = (int) ((1000L * 1000L * 1000L) / outputThrottle.getNanosPerTick()) * 4;
		} else {
			ibwLimit = ibwLimit * 4 / 5;
		}
		requestInputThrottle = 
			new TokenBucket(Math.max(ibwLimit*60, 32768*20), (1000L*1000L*1000L) / ibwLimit, 0);
		
		
		// FIXME add an averaging/long-term/soft bandwidth limit. (bug 76)
		
		// SwapRequestInterval
		
		nodeConfig.register("swapRequestSendInterval", 2000, sortOrder++, true,
				"Swap request send interval (ms)", "Interval between swap attempting to send swap requests in milliseconds. Leave this alone!",
				new IntCallback() {
					public int get() {
						return swapInterval.fixedInterval;
					}
					public void set(int val) throws InvalidConfigValueException {
						swapInterval.set(val);
					}
		});
		
		swapInterval = new StaticSwapRequestInterval(nodeConfig.getInt("swapRequestSendInterval"));
		
		// Testnet.
		// Cannot be enabled/disabled on the fly.
		// If enabled, forces certain other config options.
		
		if((testnetHandler = TestnetHandler.maybeCreate(this, config)) != null) {
			String msg = "WARNING: ENABLING TESTNET CODE! This WILL seriously jeopardize your anonymity!";
			Logger.error(this, msg);
			System.err.println(msg);
			testnetEnabled = true;
			if(logConfigHandler.getFileLoggerHook() == null) {
				System.err.println("Forcing logging enabled (essential for testnet)");
				logConfigHandler.forceEnableLogging();
			}
			int x = Logger.globalGetThreshold();
			if(!((x == Logger.MINOR) || (x == Logger.DEBUG))) {
				System.err.println("Forcing log threshold to MINOR for testnet, was "+x);
				Logger.globalSetThreshold(Logger.MINOR);
			}
			if(logConfigHandler.getMaxZippedLogFiles() < TESTNET_MIN_MAX_ZIPPED_LOGFILES) {
				System.err.println("Forcing max zipped logfiles space to 256MB for testnet");
				try {
					logConfigHandler.setMaxZippedLogFiles(TESTNET_MIN_MAX_ZIPPED_LOGFILES_STRING);
				} catch (InvalidConfigValueException e) {
					throw new Error("Impossible: "+e);
				}
			}
		} else {
			String s = "Testnet mode DISABLED. You may have some level of anonymity. :)\n"+
				"Note that while we no longer have explicit back-doors enabled, this version of Freenet is still a very early alpha, and may well have numerous bugs and design flaws.\n"+
				"In particular: YOU ARE WIDE OPEN TO YOUR IMMEDIATE DARKNET PEERS! They can eavesdrop on your requests with relatively little difficulty at present (correlation attacks etc).";
			Logger.normal(this, s);
			System.err.println(s);
			testnetEnabled = false;
			if(wasTestnet) {
				FileLoggerHook flh = logConfigHandler.getFileLoggerHook();
				if(flh != null) flh.deleteAllOldLogFiles();
			}
		}
		
		if(wasTestnet != testnetEnabled) {
			Logger.error(this, "Switched from testnet mode to non-testnet mode or vice versa! Regenerating pubkey, privkey, and deleting logs.");
			this.myCryptoGroup = Global.DSAgroupBigA;
			this.myPrivKey = new DSAPrivateKey(myCryptoGroup, random);
			this.myPubKey = new DSAPublicKey(myCryptoGroup, myPrivKey);
			
			
		}

		// Directory for node-related files other than store
		
		nodeConfig.register("nodeDir", ".", sortOrder++, true, "Node directory", "Name of directory to put node-related files e.g. peers list in", 
				new StringCallback() {
					public String get() {
						return nodeDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(nodeDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving node directory on the fly not supported at present");
					}
		});
		
		nodeDir = new File(nodeConfig.getString("nodeDir"));
		if(!((nodeDir.exists() && nodeDir.isDirectory()) || (nodeDir.mkdir()))) {
			String msg = "Could not find or create datastore directory";
			throw new NodeInitException(EXIT_BAD_NODE_DIR, msg);
		}

		// After we have set up testnet and IP address, load the node file
		try {
			// FIXME should take file directly?
			readNodeFile(new File(nodeDir, "node-"+portNumber).getPath(), random);
		} catch (IOException e) {
			try {
				readNodeFile(new File("node-"+portNumber+".bak").getPath(), random);
			} catch (IOException e1) {
				initNodeFileSettings(random);
			}
		}

		// Prepare the ARKFetchManager
		arkFetchManager = new ARKFetchManager(this);

		// Then read the peers
		peers = new PeerManager(this, new File(nodeDir, "peers-"+portNumber).getPath());
		peers.writePeers();
		peers.updatePMUserAlert();
		nodePinger = new NodePinger(this);

		usm.setDispatcher(dispatcher=new NodeDispatcher(this));
		usm.setLowLevelFilter(packetMangler = new FNPPacketMangler(this));
		
		// Extra Peer Data Directory
		nodeConfig.register("extraPeerDataDir", new File(nodeDir, "extra-peer-data-"+portNumber).toString(), sortOrder++, true, "Extra peer data directory", "Name of directory to put extra peer data in",
				new StringCallback() {
					public String get() {
						return extraPeerDataDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(extraPeerDataDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving node directory on the fly not supported at present");
					}
		});
		extraPeerDataDir = new File(nodeConfig.getString("extraPeerDataDir"));
		if(!((extraPeerDataDir.exists() && extraPeerDataDir.isDirectory()) || (extraPeerDataDir.mkdir()))) {
			String msg = "Could not find or create extra peer data directory";
			throw new NodeInitException(EXIT_EXTRA_PEER_DATA_DIR, msg);
		}
		
        // Name 	 
        nodeConfig.register("name", myName, sortOrder++, false, "Node name for darknet", "Node name; you may want to set this to something descriptive if running on darknet e.g. Fred Blogg's Node; it is visible to any connecting node", 	 
                        new NodeNameCallback(this)); 	 
        myName = nodeConfig.getString("name"); 	 

		
		// Datastore
		nodeConfig.register("storeSize", "1G", sortOrder++, false, "Store size in bytes", "Store size in bytes", 
				new LongCallback() {

					public long get() {
						return maxTotalKeys * sizePerKey;
					}

					public void set(long storeSize) throws InvalidConfigValueException {
						if((storeSize < 0) || (storeSize < (32 * 1024 * 1024)))
							throw new InvalidConfigValueException("Invalid store size");
						long newMaxStoreKeys = storeSize / sizePerKey;
						if(newMaxStoreKeys == maxTotalKeys) return;
						// Update each datastore
						synchronized(Node.this) {
							maxTotalKeys = newMaxStoreKeys;
							maxStoreKeys = (maxTotalKeys * 4) / 5;
							maxCacheKeys = maxTotalKeys - maxStoreKeys;
						}
						try {
							long sz;
							chkDatastore.setMaxKeys(maxStoreKeys, false);
							sz = Math.max(maxCacheKeys, maxTotalKeys - chkDatastore.keyCount());
							chkDatacache.setMaxKeys(sz, false);
							pubKeyDatastore.setMaxKeys(maxStoreKeys, false);
							sz = Math.max(maxCacheKeys, maxTotalKeys - pubKeyDatastore.keyCount());
							pubKeyDatacache.setMaxKeys(sz, false);
							sskDatastore.setMaxKeys(maxStoreKeys, false);
							sz = Math.max(maxCacheKeys, maxTotalKeys - sskDatastore.keyCount());
							sskDatacache.setMaxKeys(sz, false);
						} catch (IOException e) {
							// FIXME we need to be able to tell the user.
							Logger.error(this, "Caught "+e+" resizing the datastore", e);
							System.err.println("Caught "+e+" resizing the datastore");
							e.printStackTrace();
						} catch (DatabaseException e) {
							Logger.error(this, "Caught "+e+" resizing the datastore", e);
							System.err.println("Caught "+e+" resizing the datastore");
							e.printStackTrace();
						}
					}
		});
		
		long storeSize = nodeConfig.getLong("storeSize");
		
		if(/*storeSize < 0 || */storeSize < (32 * 1024 * 1024)) { // totally arbitrary minimum!
			throw new NodeInitException(EXIT_INVALID_STORE_SIZE, "Invalid store size");
		}

		maxTotalKeys = storeSize / sizePerKey;
		
		nodeConfig.register("storeDir", ".", sortOrder++, true, "Store directory", "Name of directory to put store files in", 
				new StringCallback() {
					public String get() {
						return storeDir.getPath();
					}
					public void set(String val) throws InvalidConfigValueException {
						if(storeDir.equals(new File(val))) return;
						// FIXME
						throw new InvalidConfigValueException("Moving datastore on the fly not supported at present");
					}
		});
		
		storeDir = new File(nodeConfig.getString("storeDir"));
		if(!((storeDir.exists() && storeDir.isDirectory()) || (storeDir.mkdir()))) {
			String msg = "Could not find or create datastore directory";
			throw new NodeInitException(EXIT_STORE_OTHER, msg);
		}

		
		String chkStorePath = storeDir.getPath()+File.separator+"store-"+portNumber;
		String chkCachePath = storeDir.getPath()+File.separator+"cache-"+portNumber;
		String pkStorePath  = storeDir.getPath()+File.separator+"pubkeystore-"+portNumber;
		String pkCachePath  = storeDir.getPath()+File.separator+"pubkeycache-"+portNumber;
		String sskStorePath = storeDir.getPath()+File.separator+"sskstore-"+portNumber;
		String sskCachePath = storeDir.getPath()+File.separator+"sskcache-"+portNumber;
		File chkStoreFile = new File(chkStorePath);
		File chkCacheFile = new File(chkCachePath);
		File pkStoreFile = new File(pkStorePath);
		File pkCacheFile = new File(pkCachePath);
		File sskStoreFile = new File(sskStorePath);
		File sskCacheFile = new File(sskCachePath);
		
		// Upgrade
		if(this.lastVersion < 927) {
			if(chkStoreFile.exists() && !chkCacheFile.exists()) {
				System.err.println("Renaming CHK store to CHK cache.");
				if(!chkStoreFile.renameTo(chkCacheFile))
					throw new NodeInitException(EXIT_STORE_OTHER, "Could not migrate to two level cache: Could not rename "+chkStoreFile+" to "+chkCacheFile);
			}
			if(pkStoreFile.exists() && !pkCacheFile.exists()) {
				System.err.println("Renaming PK store to PK cache.");
				if(!pkStoreFile.renameTo(pkCacheFile))
					throw new NodeInitException(EXIT_STORE_OTHER, "Could not migrate to two level cache: Could not rename "+pkStoreFile+" to "+pkCacheFile);
			}
			if(sskStoreFile.exists() && !sskCacheFile.exists()) {
				System.err.println("Renaming SSK store to SSK cache.");
				if(!sskStoreFile.renameTo(sskCacheFile))
					throw new NodeInitException(EXIT_STORE_OTHER, "Could not migrate to two level cache: Could not rename "+sskStoreFile+" to "+sskCacheFile);
			}
		}

		maxStoreKeys = (maxTotalKeys * 4) / 5;
		maxCacheKeys = maxTotalKeys - maxStoreKeys;
		
		try {
			BerkeleyDBFreenetStore tmp;
			long sz;
			Logger.normal(this, "Initializing CHK Datastore");
			System.out.println("Initializing CHK Datastore ("+maxStoreKeys+" keys)");
			try {
				if((lastVersion > 0) && (lastVersion < 852)) {
					throw new DatabaseException("Reconstructing store because started from old version");
				}
				tmp = new BerkeleyDBFreenetStore(chkStorePath, maxStoreKeys, 32768, CHKBlock.TOTAL_HEADERS_LENGTH, true);
			} catch (DatabaseException e) {
				System.err.println("Could not open store: "+e);
				e.printStackTrace();
				System.err.println("Attempting to reconstruct...");
				WrapperManager.signalStarting(5*60*60*1000);
				tmp = new BerkeleyDBFreenetStore(chkStorePath, maxStoreKeys, 32768, CHKBlock.TOTAL_HEADERS_LENGTH, BerkeleyDBFreenetStore.TYPE_CHK);
			}
			chkDatastore = tmp;
			Logger.normal(this, "Initializing CHK Datacache");
			sz = Math.max(maxCacheKeys, maxTotalKeys - tmp.keyCount());
			System.out.println("Initializing CHK Datacache ("+sz+":"+maxCacheKeys+" keys)");
			try {
				if((lastVersion > 0) && (lastVersion < 852)) {
					throw new DatabaseException("Reconstructing store because started from old version");
				}
				tmp = new BerkeleyDBFreenetStore(chkCachePath, sz, 32768, CHKBlock.TOTAL_HEADERS_LENGTH, true);
			} catch (DatabaseException e) {
				System.err.println("Could not open store: "+e);
				e.printStackTrace();
				System.err.println("Attempting to reconstruct...");
				WrapperManager.signalStarting(5*60*60*1000);
				tmp = new BerkeleyDBFreenetStore(chkCachePath, sz, 32768, CHKBlock.TOTAL_HEADERS_LENGTH, BerkeleyDBFreenetStore.TYPE_CHK);
			}
			chkDatacache = tmp;
			chkDatacache.setMaxKeys(maxCacheKeys, false);
			// Shrink pubkey store immediately; it's tiny anyway.
			Logger.normal(this, "Initializing pubKey Datastore");
			System.out.println("Initializing pubKey Datastore");
			try {
				if((lastVersion > 0) && (lastVersion < 852)) {
					throw new DatabaseException("Reconstructing store because started from old version");
				}
				tmp = new BerkeleyDBFreenetStore(pkStorePath, maxStoreKeys, DSAPublicKey.PADDED_SIZE, 0, true);
			} catch (DatabaseException e) {
				System.err.println("Could not open store: "+e);
				e.printStackTrace();
				System.err.println("Attempting to reconstruct...");
				WrapperManager.signalStarting(5*60*60*1000);
				tmp = new BerkeleyDBFreenetStore(pkStorePath, maxStoreKeys, DSAPublicKey.PADDED_SIZE, 0, BerkeleyDBFreenetStore.TYPE_PUBKEY);
			}
			this.pubKeyDatastore = tmp;
			Logger.normal(this, "Initializing pubKey Datacache");
			System.out.println("Initializing pubKey Datacache ("+maxCacheKeys+" keys)");
			try {
				if((lastVersion > 0) && (lastVersion < 852)) {
					throw new DatabaseException("Reconstructing store because started from old version");
				}
				tmp = new BerkeleyDBFreenetStore(pkCachePath, maxCacheKeys, DSAPublicKey.PADDED_SIZE, 0, true);
			} catch (DatabaseException e) {
				System.err.println("Could not open store: "+e);
				e.printStackTrace();
				System.err.println("Attempting to reconstruct...");
				WrapperManager.signalStarting(5*60*60*1000);
				tmp = new BerkeleyDBFreenetStore(pkCachePath, maxCacheKeys, DSAPublicKey.PADDED_SIZE, 0, BerkeleyDBFreenetStore.TYPE_PUBKEY);
			}
			this.pubKeyDatacache = tmp;
			// FIXME can't auto-fix SSK stores.
			Logger.normal(this, "Initializing SSK Datastore");
			System.out.println("Initializing SSK Datastore");
			sskDatastore = new BerkeleyDBFreenetStore(sskStorePath, maxStoreKeys, 1024, SSKBlock.TOTAL_HEADERS_LENGTH, false);
			Logger.normal(this, "Initializing SSK Datacache");
			sz = Math.max(maxCacheKeys, maxTotalKeys - sskDatastore.keyCount());
			System.out.println("Initializing SSK Datacache ("+sz+":"+maxCacheKeys+" keys)");
			sskDatacache = new BerkeleyDBFreenetStore(sskCachePath, sz, 1024, SSKBlock.TOTAL_HEADERS_LENGTH, false);
			sskDatacache.setMaxKeys(maxCacheKeys, false);
		} catch (FileNotFoundException e1) {
			String msg = "Could not open datastore: "+e1;
			Logger.error(this, msg, e1);
			System.err.println(msg);
			throw new NodeInitException(EXIT_STORE_FILE_NOT_FOUND, msg);
		} catch (IOException e1) {
			String msg = "Could not open datastore: "+e1;
			Logger.error(this, msg, e1);
			System.err.println(msg);
			throw new NodeInitException(EXIT_STORE_IOEXCEPTION, msg);
		} catch (Exception e1) {
			String msg = "Could not open datastore: "+e1;
			Logger.error(this, msg, e1);
			System.err.println(msg);
			e1.printStackTrace();
			throw new NodeInitException(EXIT_STORE_OTHER, msg);
		}
		
		nodeConfig.register("throttleFile", "throttle.dat", sortOrder++, true, "File to store the persistent throttle data to", "File to store the persistent throttle data to", new StringCallback() {

			public String get() {
				return persistTarget.toString();
			}

			public void set(String val) throws InvalidConfigValueException {
				setThrottles(val);
			}
			
		});
		
		String throttleFile = nodeConfig.getString("throttleFile");
		try {
			setThrottles(throttleFile);
		} catch (InvalidConfigValueException e2) {
			throw new NodeInitException(EXIT_THROTTLE_FILE_ERROR, e2.getMessage());
		}
		
		throttlePersister = new ThrottlePersister();
		
		SimpleFieldSet throttleFS = null;
		try {
			throttleFS = SimpleFieldSet.readFrom(persistTarget);
		} catch (IOException e) {
			try {
				throttleFS = SimpleFieldSet.readFrom(persistTemp);
			} catch (FileNotFoundException e1) {
				// Ignore
			} catch (IOException e1) {
				Logger.error(this, "Could not read "+persistTarget+" ("+e+") and could not read "+persistTemp+" either ("+e1+")");
			}
		}

		Logger.minor(this, "Read throttleFS:\n"+throttleFS);
		
		// Guesstimates. Hopefully well over the reality.
		localChkFetchBytesSentAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("LocalChkFetchBytesSentAverage"));
		localSskFetchBytesSentAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("LocalSskFetchBytesSentAverage"));
		localChkInsertBytesSentAverage = new TimeDecayingRunningAverage(32768, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesSentAverage"));
		localSskInsertBytesSentAverage = new TimeDecayingRunningAverage(2048, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("LocalSskInsertBytesSentAverage"));
		localChkFetchBytesReceivedAverage = new TimeDecayingRunningAverage(32768, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("LocalChkFetchBytesReceivedAverage"));
		localSskFetchBytesReceivedAverage = new TimeDecayingRunningAverage(2048, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("LocalSskFetchBytesReceivedAverage"));
		localChkInsertBytesReceivedAverage = new TimeDecayingRunningAverage(1024, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesReceivedAverage"));
		localSskInsertBytesReceivedAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesReceivedAverage"));

		remoteChkFetchBytesSentAverage = new TimeDecayingRunningAverage(32768+1024+500, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("RemoteChkFetchBytesSentAverage"));
		remoteSskFetchBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("RemoteSskFetchBytesSentAverage"));
		remoteChkInsertBytesSentAverage = new TimeDecayingRunningAverage(32768+32768+1024, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("RemoteChkInsertBytesSentAverage"));
		remoteSskInsertBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("RemoteSskInsertBytesSentAverage"));
		remoteChkFetchBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("RemoteChkFetchBytesReceivedAverage"));
		remoteSskFetchBytesReceivedAverage = new TimeDecayingRunningAverage(2048+500, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("RemoteSskFetchBytesReceivedAverage"));
		remoteChkInsertBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("RemoteChkInsertBytesReceivedAverage"));
		remoteSskInsertBytesReceivedAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, Long.MAX_VALUE, throttleFS == null ? null : throttleFS.subset("RemoteSskInsertBytesReceivedAverage"));
		
		clientCore = new NodeClientCore(this, config, nodeConfig, nodeDir, portNumber, sortOrder, throttleFS == null ? null : throttleFS.subset("RequestStarters"));
		
		nodeConfig.finishedInitialization();
		writeNodeFile();
		
		// And finally, Initialize the plugin manager
		Logger.normal(this, "Initializing Plugin Manager");
		System.out.println("Initializing Plugin Manager");
		pluginManager = new PluginManager(this);
		
		FetcherContext ctx = clientCore.makeClient((short)0).getFetcherContext();
		
		ctx.allowSplitfiles = false;
		ctx.dontEnterImplicitArchives = true;
		ctx.maxArchiveRestarts = 0;
		ctx.maxMetadataSize = 256;
		ctx.maxNonSplitfileRetries = 10;
		ctx.maxOutputLength = 4096;
		ctx.maxRecursionLevel = 2;
		ctx.maxTempLength = 4096;
		
		this.arkFetcherContext = ctx;
		Logger.normal(this, "Node constructor completed");
		System.out.println("Node constructor completed");
	}
	
	private void setThrottles(String val) throws InvalidConfigValueException {
		File f = new File(val);
		File tmp = new File(val+".tmp");
		while(true) {
			if(f.exists()) {
				if(!(f.canRead() && f.canWrite()))
					throw new InvalidConfigValueException("File exists and cannot read/write it");
				break;
			} else {
				try {
					f.createNewFile();
				} catch (IOException e) {
					throw new InvalidConfigValueException("File does not exist and cannot be created");
				}
			}
		}
		while(true) {
			if(tmp.exists()) {
				if(!(tmp.canRead() && tmp.canWrite()))
					throw new InvalidConfigValueException("File exists and cannot read/write it");
				break;
			} else {
				try {
					tmp.createNewFile();
				} catch (IOException e) {
					throw new InvalidConfigValueException("File does not exist and cannot be created");
				}
			}
		}
		
		ThrottlePersister tp;
		synchronized(Node.this) {
			persistTarget = f;
			persistTemp = tmp;
			tp = throttlePersister;
		}
		if(tp != null)
			tp.interrupt();
	}

	static final String ERROR_SUN_NPTL = 
		"WARNING: Your system appears to be running a Sun JVM with NPTL. " +
		"This has been known to cause the node to freeze up due to the JVM losing a lock. " +
		"Please disable NPTL if possible by setting the environment variable LD_ASSUME_KERNEL=2.4.1. " +
		"Recent versions of the freenet installer should have this already; either reinstall, or edit " +
		"run.sh (https://emu.freenetproject.org/svn/trunk/apps/installer/installclasspath/run.sh). " +
		"On some systems you may need to install the pthreads libraries to make this work. " +
		"Note that the node will try to automatically restart the node in the event of such a deadlock, " +
		"but this will cause some disruption, and may not be 100% reliable.";
	
	void start(boolean noSwaps) throws NodeInitException {
		if(!noSwaps)
			lm.startSender(this, this.swapInterval);
		nodePinger.start();
		dnsr.start();
		ps.start();
		usm.start();
		myMemoryChecker.start();
		peers.start();
		
		if(isUsingWrapper()) {
			Logger.normal(this, "Using wrapper correctly: "+nodeStarter);
			System.out.println("Using wrapper correctly: "+nodeStarter);
		} else {
			Logger.error(this, "NOT using wrapper (at least not correctly).  Your freenet-ext.jar <http://downloads.freenetproject.org/alpha/freenet-ext.jar> and/or wrapper.conf <https://emu.freenetproject.org/svn/trunk/apps/installer/installclasspath/config/wrapper.conf> need to be updated.");
			System.out.println("NOT using wrapper (at least not correctly).  Your freenet-ext.jar <http://downloads.freenetproject.org/alpha/freenet-ext.jar> and/or wrapper.conf <https://emu.freenetproject.org/svn/trunk/apps/installer/installclasspath/config/wrapper.conf> need to be updated.");
		}
		Logger.normal(this, "Freenet 0.7 Build #"+Version.buildNumber()+" r"+Version.cvsRevision);
		System.out.println("Freenet 0.7 Build #"+Version.buildNumber()+" r"+Version.cvsRevision);
		Logger.normal(this, "FNP port is on "+bindto+":"+portNumber);
		System.out.println("FNP port is on "+bindto+":"+portNumber);
		// Start services
		
		pluginManager2 = new freenet.plugin.PluginManager(this);
		
//		SubConfig pluginManagerConfig = new SubConfig("pluginmanager3", config);
//		pluginManager3 = new freenet.plugin_new.PluginManager(pluginManagerConfig);
		
		// Node Updater
		try{
			nodeUpdater = NodeUpdater.maybeCreate(this, config);
			Logger.normal(this, "Starting the node updater");
		}catch (Exception e) {
			e.printStackTrace();
			throw new NodeInitException(EXIT_COULD_NOT_START_UPDATER, "Could not start Updater: "+e);
		}
		
		/*
		SimpleToadletServer server = new SimpleToadletServer(port+2000);
		FProxyToadlet fproxy = new FProxyToadlet(n.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS));
		PProxyToadlet pproxy = new PProxyToadlet(n.makeClient(RequestStarter.INTERACTIVE_PRIORITY_CLASS), n.pluginManager);
		server.register(fproxy, "/", false);
		server.register(pproxy, "/plugins/", true);
		 * */
		
		ipDetector.start();
		
		// Start testnet handler
		if(testnetHandler != null)
			testnetHandler.start();
		
		checkForEvilJVMBug();
		
		// TODO: implement a "required" version if needed
		if(NodeStarter.RECOMMENDED_EXT_BUILD_NUMBER > NodeStarter.extBuildNumber)
			clientCore.alerts.register(new ExtOldAgeUserAlert());
		else if(NodeStarter.extBuildNumber == -1)
			clientCore.alerts.register(new ExtOldAgeUserAlert());
		
		this.clientCore.start(config);
		
		// After everything has been created, write the config file back to disk.
		config.finishedInit();
		config.store();
		
		// Process any data in the extra peer data directory
		peers.readExtraPeerData();
		
		Thread t = new Thread(throttlePersister, "Throttle data persister thread");
		t.setDaemon(true);
		t.start();
		
		hasStarted = true;
	}
	
	private void checkForEvilJVMBug() {
		// Now check whether we are likely to get the EvilJVMBug.
		// If we are running a Sun or Blackdown JVM, on Linux, and LD_ASSUME_KERNEL is not set, then we are.
		
		String jvmVendor = System.getProperty("java.vm.vendor");
		String jvmVersion = System.getProperty("java.vm.version");
		String osName = System.getProperty("os.name");
		String osVersion = System.getProperty("os.version");
		
		Logger.minor(this, "JVM vendor: "+jvmVendor+", JVM version: "+jvmVersion+", OS name: "+osName+", OS version: "+osVersion);
		
		// If we are using the wrapper, we ignore:
		// Any problem should be detected by the watchdog and the node will be restarted
		if(osName.equals("Linux") && jvmVendor.startsWith("Sun ") && 
				((osVersion.indexOf("nptl")!=-1) || osVersion.startsWith("2.6") || 
						osVersion.startsWith("2.7") || osVersion.startsWith("3."))
						&& !isUsingWrapper()) {
			// Hopefully we won't still have to deal with this **** when THAT comes out! 
			// Check the environment.
			String assumeKernel;
			try {
				// It is essential to check the environment.
				// Make an alternative way to do it if you like.
				assumeKernel = System.getenv("LD_ASSUME_KERNEL");
			} catch (Error e) {
				assumeKernel = null;
				assumeKernel = WrapperManager.getProperties().getProperty("set.LD_ASSUME_KERNEL");
			}
			if((assumeKernel == null) || (assumeKernel.length() == 0) || (!(assumeKernel.startsWith("2.2") || assumeKernel.startsWith("2.4")))) {
				System.err.println(ERROR_SUN_NPTL);
				Logger.error(this, ERROR_SUN_NPTL);
				clientCore.alerts.register(new UserAlert() {

					public boolean userCanDismiss() {
						return false;
					}

					public String getTitle() {
						return "Deadlocking likely due to buggy JVM/kernel combination";
					}

					public String getText() {
						return ERROR_SUN_NPTL;
					}

					public HTMLNode getHTMLText() {
						return new HTMLNode("div", ERROR_SUN_NPTL);
					}

					public short getPriorityClass() {
						return UserAlert.CRITICAL_ERROR;
					}

					public boolean isValid() {
						return true;
					}

					public void isValid(boolean validity) {
						// Not clearable.
					}

					public String dismissButtonText() {
						// Not dismissable.
						return null;
					}

					public boolean shouldUnregisterOnDismiss() {
						// Not dismissable.
						return false;
					}

					public void onDismiss() {
						// Not dismissable.
					}
				});
			}
		}
	}

	private long lastAcceptedRequest = -1;
	
	private long lastCheckedUncontended = -1;
	
	static final int ESTIMATED_SIZE_OF_ONE_THROTTLED_PACKET = 
		1024 + DMT.packetTransmitSize(1024, 32)
		+ FNPPacketMangler.HEADERS_LENGTH_ONE_MESSAGE;
	
    /* return reject reason as string if should reject, otherwise return null */
	public String shouldRejectRequest(boolean canAcceptAnyway, boolean isInsert, boolean isSSK) {
		dumpByteCostAverages();
		
		double bwlimitDelayTime = throttledPacketSendAverage.currentValue();
		
		// If no recent reports, no packets have been sent; correct the average downwards.
		long now = System.currentTimeMillis();
		boolean checkUncontended = false;
		synchronized(this) {
			if(now - lastCheckedUncontended > 1000) {
				checkUncontended = true;
				lastCheckedUncontended = now;
			}
		}
		if(checkUncontended && throttledPacketSendAverage.lastReportTime() < now - 5000) {  // if last report more than 5 seconds ago
			// shouldn't take long
			outputThrottle.blockingGrab(ESTIMATED_SIZE_OF_ONE_THROTTLED_PACKET);
			outputThrottle.recycle(ESTIMATED_SIZE_OF_ONE_THROTTLED_PACKET);
			long after = System.currentTimeMillis();
			// Report time it takes to grab the bytes.
			throttledPacketSendAverage.report(after - now);
			now = after;
			// will have changed, use new value
			synchronized(this) {
				bwlimitDelayTime = throttledPacketSendAverage.currentValue();
			}
		}
		
		double pingTime = nodePinger.averagePingTime();
		synchronized(this) {
			// Round trip time
			if(pingTime > MAX_PING_TIME) {
				if((now - lastAcceptedRequest > MAX_INTERREQUEST_TIME) && canAcceptAnyway) {
					Logger.minor(this, "Accepting request anyway (take one every 10 secs to keep bwlimitDelayTime updated)");
					lastAcceptedRequest = now;
					pInstantRejectIncoming.report(0.0);
					return null;
				}
				pInstantRejectIncoming.report(1.0);
				return ">MAX_PING_TIME ("+pingTime+")";
			}
			if(pingTime > SUB_MAX_PING_TIME) {
				double x = ((double)(pingTime - SUB_MAX_PING_TIME)) / (MAX_PING_TIME - SUB_MAX_PING_TIME);
				if(random.nextDouble() < x) {
					pInstantRejectIncoming.report(1.0);
					return ">SUB_MAX_PING_TIME ("+pingTime+")";
				}
			}
		
			// Bandwidth limited packets
			if(bwlimitDelayTime > MAX_THROTTLE_DELAY) {
				if((now - lastAcceptedRequest > MAX_INTERREQUEST_TIME) && canAcceptAnyway) {
					Logger.minor(this, "Accepting request anyway (take one every 10 secs to keep bwlimitDelayTime updated)");
					lastAcceptedRequest = now;
					pInstantRejectIncoming.report(0.0);
					return null;
				}
				pInstantRejectIncoming.report(1.0);
				return ">MAX_THROTTLE_DELAY ("+bwlimitDelayTime+")";
			}
			if(bwlimitDelayTime > SUB_MAX_THROTTLE_DELAY) {
				double x = ((double)(bwlimitDelayTime - SUB_MAX_THROTTLE_DELAY)) / (MAX_THROTTLE_DELAY - SUB_MAX_THROTTLE_DELAY);
				if(random.nextDouble() < x) {
					pInstantRejectIncoming.report(1.0);
					return ">SUB_MAX_THROTTLE_DELAY ("+bwlimitDelayTime+")";
				}
			}
			
			Logger.minor(this, "Accepting request?");
			
			lastAcceptedRequest = now;
		}
		
		// Do we have the bandwidth?
		double expected =
			(isInsert ? (isSSK ? this.remoteSskInsertBytesSentAverage : this.remoteChkInsertBytesSentAverage)
					: (isSSK ? this.remoteSskFetchBytesSentAverage : this.remoteChkFetchBytesSentAverage)).currentValue();
		int expectedSent = (int)Math.max(expected, 0);
		if(!requestOutputThrottle.instantGrab(expectedSent)) {
			pInstantRejectIncoming.report(1.0);
			return "Insufficient output bandwidth";
		}
		expected = 
			(isInsert ? (isSSK ? this.remoteSskInsertBytesReceivedAverage : this.remoteChkInsertBytesReceivedAverage)
					: (isSSK ? this.remoteSskFetchBytesReceivedAverage : this.remoteChkFetchBytesReceivedAverage)).currentValue();
		int expectedReceived = (int)Math.max(expected, 0);
		if(!requestInputThrottle.instantGrab(expectedReceived)) {
			requestOutputThrottle.recycle(expectedSent);
			pInstantRejectIncoming.report(1.0);
			return "Insufficient input bandwidth";
		}

		pInstantRejectIncoming.report(0.0);

		// Accept
		return null;
	}
	
	private void dumpByteCostAverages() {
		Logger.minor(this, "Byte cost averages: REMOTE:"+
				" CHK insert "+remoteChkInsertBytesSentAverage.currentValue()+"/"+remoteChkInsertBytesReceivedAverage.currentValue()+
				" SSK insert "+remoteSskInsertBytesSentAverage.currentValue()+"/"+remoteSskInsertBytesReceivedAverage.currentValue()+
				" CHK fetch "+remoteChkFetchBytesSentAverage.currentValue()+"/"+remoteChkFetchBytesReceivedAverage.currentValue()+
				" SSK fetch "+remoteSskFetchBytesSentAverage.currentValue()+"/"+remoteSskFetchBytesReceivedAverage.currentValue());
		Logger.minor(this, "Byte cost averages: LOCAL"+
				" CHK insert "+localChkInsertBytesSentAverage.currentValue()+"/"+localChkInsertBytesReceivedAverage.currentValue()+
				" SSK insert "+localSskInsertBytesSentAverage.currentValue()+"/"+localSskInsertBytesReceivedAverage.currentValue()+
				" CHK fetch "+localChkFetchBytesSentAverage.currentValue()+"/"+localChkFetchBytesReceivedAverage.currentValue()+
				" SSK fetch "+localSskFetchBytesSentAverage.currentValue()+"/"+localSskFetchBytesReceivedAverage.currentValue());
	}

	public SimpleFieldSet exportPrivateFieldSet() {
		SimpleFieldSet fs = exportPublicFieldSet();
		fs.put("dsaPrivKey", myPrivKey.asFieldSet());
		fs.put("ark.privURI", this.myARK.getInsertURI().toString(false));
		return fs;
	}
	
	/**
	 * Export my reference so that another node can connect to me.
	 * @return
	 */
	public SimpleFieldSet exportPublicFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		Peer[] ips = ipDetector.getPrimaryIPAddress();
		if(ips != null) {
			for(int i=0;i<ips.length;i++)
				fs.put("physical.udp", ips[i].toString());
		}
		fs.put("identity", Base64.encode(myIdentity));
		fs.put("location", Double.toString(lm.getLocation().getValue()));
		fs.put("version", Version.getVersionString());
		fs.put("testnet", Boolean.toString(testnetEnabled));
		fs.put("lastGoodVersion", Version.getLastGoodVersionString());
		if(testnetEnabled)
			fs.put("testnetPort", Integer.toString(testnetHandler.getPort()));
		fs.put("myName", myName);
		fs.put("dsaGroup", myCryptoGroup.asFieldSet());
		fs.put("dsaPubKey", myPubKey.asFieldSet());
		fs.put("ark.number", Long.toString(this.myARKNumber));
		fs.put("ark.pubURI", this.myARK.getURI().toString(false));
		Logger.minor(this, "My reference: "+fs);
		return fs;
	}

	/**
	 * Do a routed ping of another node on the network by its location.
	 * @param loc2 The location of the other node to ping. It must match
	 * exactly.
	 * @return The number of hops it took to find the node, if it was found.
	 * Otherwise -1.
	 */
	public int routedPing(double loc2) {
		long uid = random.nextLong();
		int initialX = random.nextInt();
		Message m = DMT.createFNPRoutedPing(uid, loc2, MAX_HTL, initialX);
		Logger.normal(this, "Message: "+m);
		
		dispatcher.handleRouted(m);
		// FIXME: might be rejected
		MessageFilter mf1 = MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPRoutedPong).setTimeout(5000);
		try {
			//MessageFilter mf2 = MessageFilter.create().setField(DMT.UID, uid).setType(DMT.FNPRoutedRejected).setTimeout(5000);
			// Ignore Rejected - let it be retried on other peers
			m = usm.waitFor(mf1/*.or(mf2)*/, null);
		} catch (DisconnectedException e) {
			Logger.normal(this, "Disconnected in waiting for pong");
			return -1;
		}
		if(m == null) return -1;
		if(m.getSpec() == DMT.FNPRoutedRejected) return -1;
		return m.getInt(DMT.COUNTER) - initialX;
	}

	/**
	 * Check the datastore, then if the key is not in the store,
	 * check whether another node is requesting the same key at
	 * the same HTL, and if all else fails, create a new 
	 * RequestSender for the key/htl.
	 * @param closestLocation The closest location to the key so far.
	 * @param localOnly If true, only check the datastore.
	 * @return A CHKBlock if the data is in the store, otherwise
	 * a RequestSender, unless the HTL is 0, in which case NULL.
	 * RequestSender.
	 */
	public Object makeRequestSender(Key key, short htl, long uid, PeerNode source, double closestLocation, boolean resetClosestLocation, boolean localOnly, boolean cache, boolean ignoreStore) {
		Logger.minor(this, "makeRequestSender("+key+","+htl+","+uid+","+source+") on "+portNumber);
		// In store?
		KeyBlock chk = null;
		if(!ignoreStore) {
			if(key instanceof NodeCHK) {
				chk = fetch((NodeCHK)key, !cache);
			} else if(key instanceof NodeSSK) {
				NodeSSK k = (NodeSSK)key;
				DSAPublicKey pubKey = k.getPubKey();
				if(pubKey == null) {
					pubKey = getKey(k.getPubKeyHash());
					Logger.minor(this, "Fetched pubkey: "+pubKey+" "+(pubKey == null ? "" : pubKey.writeAsField()));
					try {
						k.setPubKey(pubKey);
					} catch (SSKVerifyException e) {
						Logger.error(this, "Error setting pubkey: "+e, e);
					}
				}
				if(pubKey != null) {
					Logger.minor(this, "Got pubkey: "+pubKey+" "+pubKey.writeAsField());
					chk = fetch((NodeSSK)key, !cache);
				} else {
					Logger.minor(this, "Not found because no pubkey: "+uid);
				}
			} else
				throw new IllegalStateException("Unknown key type: "+key.getClass());
			if(chk != null) return chk;
		}
		if(localOnly) return null;
		Logger.minor(this, "Not in store locally");
		
		// Transfer coalescing - match key only as HTL irrelevant
		RequestSender sender = null;
		synchronized(transferringRequestSenders) {
			sender = (RequestSender) transferringRequestSenders.get(key);
		}
		if(sender != null) {
			Logger.minor(this, "Data already being transferred: "+sender);
			return sender;
		}

		// HTL == 0 => Don't search further
		if(htl == 0) {
			Logger.minor(this, "No HTL");
			return null;
		}
		
		synchronized(requestSenders) {
			// Request coalescing
			KeyHTLPair kh = new KeyHTLPair(key, htl);
			sender = (RequestSender) requestSenders.get(kh);
			if(sender != null) {
				Logger.minor(this, "Found sender: "+sender+" for "+uid);
				return sender;
			}
			
			sender = new RequestSender(key, null, htl, uid, this, closestLocation, resetClosestLocation, source);
			// RequestSender adds itself to requestSenders
		}
		sender.start();
		Logger.minor(this, "Created new sender: "+sender);
		return sender;
	}
	
	static class KeyHTLPair {
		final Key key;
		final short htl;
		KeyHTLPair(Key key, short htl) {
			this.key = key;
			this.htl = htl;
		}
		
		public boolean equals(Object o) {
			if(o instanceof KeyHTLPair) {
				KeyHTLPair p = (KeyHTLPair) o;
				return (p.key.equals(key) && (p.htl == htl));
			} else return false;
		}
		
		public int hashCode() {
			return key.hashCode() ^ htl;
		}
		
		public String toString() {
			return key.toString()+":"+htl;
		}
	}

	/**
	 * Add a RequestSender to our HashMap.
	 */
	public void addRequestSender(Key key, short htl, RequestSender sender) {
		synchronized(requestSenders) {
			KeyHTLPair kh = new KeyHTLPair(key, htl);
			if(requestSenders.containsKey(kh)) {
				RequestSender rs = (RequestSender) requestSenders.get(kh);
				Logger.error(this, "addRequestSender(): KeyHTLPair '"+kh+"' already in requestSenders as "+rs+" and you want to add "+sender);
				return;
			}
			requestSenders.put(kh, sender);
		}
	}

	/**
	 * Add a AnyInsertSender to our HashMap.
	 */
	public void addInsertSender(Key key, short htl, AnyInsertSender sender) {
		synchronized(insertSenders) {
			KeyHTLPair kh = new KeyHTLPair(key, htl);
			if(insertSenders.containsKey(kh)) {
				AnyInsertSender is = (AnyInsertSender) insertSenders.get(kh);
				Logger.error(this, "addInsertSender(): KeyHTLPair '"+kh+"' already in insertSenders as "+is+" and you want to add "+sender);
				return;
			}
			insertSenders.put(kh, sender);
		}
	}

	/**
	 * Add a transferring RequestSender to our HashMap.
	 */
	public void addTransferringSender(NodeCHK key, RequestSender sender) {
		synchronized(transferringRequestSenders) {
			transferringRequestSenders.put(key, sender);
		}
	}

	public SSKBlock fetch(NodeSSK key, boolean dontPromote) {
		dumpStoreHits();
		try {
			SSKBlock block = sskDatastore.fetch(key, dontPromote);
			if(block != null) {
				return block;
			}
			return sskDatacache.fetch(key, dontPromote);
		} catch (IOException e) {
			Logger.error(this, "Cannot fetch data: "+e, e);
			return null;
		}
	}

	public CHKBlock fetch(NodeCHK key, boolean dontPromote) {
		dumpStoreHits();
		try {
			CHKBlock block = chkDatastore.fetch(key, dontPromote);
			if(block != null) return block;
			return chkDatacache.fetch(key, dontPromote);
		} catch (IOException e) {
			Logger.error(this, "Cannot fetch data: "+e, e);
			return null;
		}
	}

	long timeLastDumpedHits;
	
	public void dumpStoreHits() {
		long now = System.currentTimeMillis();
		if(now - timeLastDumpedHits > 5000) {
			timeLastDumpedHits = now;
		} else return;
		Logger.minor(this, "Distribution of hits and misses over stores:\n"+
				"CHK Datastore: "+chkDatastore.hits()+"/"+(chkDatastore.hits()+chkDatastore.misses())+"/"+chkDatastore.keyCount()+
				"\nCHK Datacache: "+chkDatacache.hits()+"/"+(chkDatacache.hits()+chkDatacache.misses())+"/"+chkDatacache.keyCount()+
				"\nSSK Datastore: "+sskDatastore.hits()+"/"+(sskDatastore.hits()+sskDatastore.misses())+"/"+sskDatastore.keyCount()+
				"\nSSK Datacache: "+sskDatacache.hits()+"/"+(sskDatacache.hits()+sskDatacache.misses())+"/"+sskDatacache.keyCount());
	}
	
	public void store(CHKBlock block) {
		store(block, block.getKey().toNormalizedDouble());
	}
	
	/**
	 * Store a datum.
	 * @param deep If true, insert to the store as well as the cache. Do not set
	 * this to true unless the store results from an insert, and this node is the
	 * closest node to the target; see the description of chkDatastore.
	 */
	public void store(CHKBlock block, double loc) {
		boolean deep = !peers.isCloserLocation(loc);
		store(block, deep);
	}

	public void storeShallow(CHKBlock block) {
		store(block, false);
	}
	
	private void store(CHKBlock block, boolean deep) {
		try {
			if(deep) {
				chkDatastore.put(block);
				long sz = Math.max(maxCacheKeys, maxTotalKeys - chkDatastore.keyCount());
				try {
					chkDatacache.setMaxKeys(sz, false);
				} catch (DatabaseException e) {
					// Impossible
					Logger.error(this, "Caught "+e, e);
				}
			}
			chkDatacache.put(block);
		} catch (IOException e) {
			Logger.error(this, "Cannot store data: "+e, e);
		}
	}
	
	/** Store the block if this is a sink. Call for inserts. */
	public void storeInsert(SSKBlock block) throws KeyCollisionException {
		store(block, block.getKey().toNormalizedDouble());
	}

	/** Store only to the cache, and not the store. Called by requests,
	 * as only inserts cause data to be added to the store. */
	public void storeShallow(SSKBlock block) throws KeyCollisionException {
		store(block, false);
	}
	
	private void store(SSKBlock block, boolean deep) throws KeyCollisionException {
		try {
			if(deep) {
				sskDatastore.put(block, false);
				long sz = Math.max(maxCacheKeys, maxTotalKeys - sskDatastore.keyCount());
				try {
					chkDatacache.setMaxKeys(sz, false);
				} catch (DatabaseException e) {
					// Impossible
					Logger.error(this, "Caught "+e, e);
				}
			}
			sskDatacache.put(block, false);
			cacheKey(((NodeSSK)block.getKey()).getPubKeyHash(), ((NodeSSK)block.getKey()).getPubKey(), deep);
		} catch (IOException e) {
			Logger.error(this, "Cannot store data: "+e, e);
		}
	}
	
	/**
	 * Store a datum.
	 * @param deep If true, insert to the store as well as the cache. Do not set
	 * this to true unless the store results from an insert, and this node is the
	 * closest node to the target; see the description of chkDatastore.
	 */
	public void store(SSKBlock block, double loc) throws KeyCollisionException {
		boolean deep = !peers.isCloserLocation(loc);
		store(block, deep);
	}
	
	/**
	 * Remove a sender from the set of currently transferring senders.
	 */
	public void removeTransferringSender(NodeCHK key, RequestSender sender) {
		synchronized(transferringRequestSenders) {
			RequestSender rs = (RequestSender) transferringRequestSenders.remove(key);
			if(rs != sender) {
				Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+" in removeTransferringSender");
			}
		}
	}

	/**
	 * Remove a RequestSender from the map.
	 */
	public void removeRequestSender(Key key, short htl, RequestSender sender) {
		synchronized(requestSenders) {
			KeyHTLPair kh = new KeyHTLPair(key, htl);
			RequestSender rs = (RequestSender) requestSenders.remove(kh);
			if(rs != sender) {
				Logger.error(this, "Removed "+rs+" should be "+sender+" for "+key+","+htl+" in removeRequestSender");
			}
		}
	}

	/**
	 * Remove an CHKInsertSender from the map.
	 */
	public void removeInsertSender(Key key, short htl, AnyInsertSender sender) {
		synchronized(insertSenders) {
			KeyHTLPair kh = new KeyHTLPair(key, htl);
			AnyInsertSender is = (AnyInsertSender) insertSenders.remove(kh);
			if(is != sender) {
				Logger.error(this, "Removed "+is+" should be "+sender+" for "+key+","+htl+" in removeInsertSender");
			}
		}
	}

	final boolean decrementAtMax;
	final boolean decrementAtMin;
	
	/**
	 * Decrement the HTL according to the policy of the given
	 * NodePeer if it is non-null, or do something else if it is
	 * null.
	 */
	public short decrementHTL(PeerNode source, short htl) {
		if(source != null)
			return source.decrementHTL(htl);
		// Otherwise...
		if(htl >= MAX_HTL) htl = MAX_HTL;
		if(htl <= 0) htl = 1;
		if(htl == MAX_HTL) {
			if(decrementAtMax) htl--;
			return htl;
		}
		if(htl == 1) {
			if(decrementAtMin) htl--;
			return htl;
		}
		return --htl;
	}

	/**
	 * Fetch or create an CHKInsertSender for a given key/htl.
	 * @param key The key to be inserted.
	 * @param htl The current HTL. We can't coalesce inserts across
	 * HTL's.
	 * @param uid The UID of the caller's request chain, or a new
	 * one. This is obviously not used if there is already an 
	 * CHKInsertSender running.
	 * @param source The node that sent the InsertRequest, or null
	 * if it originated locally.
	 */
	public CHKInsertSender makeInsertSender(NodeCHK key, short htl, long uid, PeerNode source,
			byte[] headers, PartiallyReceivedBlock prb, boolean fromStore, double closestLoc, boolean cache) {
		Logger.minor(this, "makeInsertSender("+key+","+htl+","+uid+","+source+",...,"+fromStore);
		KeyHTLPair kh = new KeyHTLPair(key, htl);
		CHKInsertSender is = null;
		synchronized(insertSenders) {
			is = (CHKInsertSender) insertSenders.get(kh);
		}
		if(is != null) {
			Logger.minor(this, "Found "+is+" for "+kh);
			return is;
		}
  		if(fromStore && !cache)
			throw new IllegalArgumentException("From store = true but cache = false !!!");
		is = new CHKInsertSender(key, uid, headers, htl, source, this, prb, fromStore, closestLoc);
		is.start();
		Logger.minor(this, is.toString()+" for "+kh.toString());
		// CHKInsertSender adds itself to insertSenders
		return is;
	}
	
	/**
	 * Fetch or create an SSKInsertSender for a given key/htl.
	 * @param key The key to be inserted.
	 * @param htl The current HTL. We can't coalesce inserts across
	 * HTL's.
	 * @param uid The UID of the caller's request chain, or a new
	 * one. This is obviously not used if there is already an 
	 * SSKInsertSender running.
	 * @param source The node that sent the InsertRequest, or null
	 * if it originated locally.
	 */
	public SSKInsertSender makeInsertSender(SSKBlock block, short htl, long uid, PeerNode source,
			boolean fromStore, double closestLoc, boolean resetClosestLoc, boolean cache) {
		NodeSSK key = (NodeSSK) block.getKey();
		if(key.getPubKey() == null) {
			throw new IllegalArgumentException("No pub key when inserting");
		}
		if(cache)
			cacheKey(key.getPubKeyHash(), key.getPubKey(), !peers.isCloserLocation(block.getKey().toNormalizedDouble()));
		Logger.minor(this, "makeInsertSender("+key+","+htl+","+uid+","+source+",...,"+fromStore);
		KeyHTLPair kh = new KeyHTLPair(key, htl);
		SSKInsertSender is = null;
		synchronized(insertSenders) {
			is = (SSKInsertSender) insertSenders.get(kh);
		}
		if(is != null) {
			Logger.minor(this, "Found "+is+" for "+kh);
			return is;
		}
		if(fromStore && !cache)
			throw new IllegalArgumentException("From store = true but cache = false !!!");
		is = new SSKInsertSender(block, uid, htl, source, this, fromStore, closestLoc);
		is.start();
		Logger.minor(this, is.toString()+" for "+kh.toString());
		// SSKInsertSender adds itself to insertSenders
		return is;
	}
	
	public boolean lockUID(long uid) {
		Logger.minor(this, "Locking "+uid);
		Long l = new Long(uid);
		synchronized(runningUIDs) {
			if(runningUIDs.contains(l)) return false;
			runningUIDs.add(l);
			return true;
		}
	}
	
	public void unlockUID(long uid) {
		Logger.minor(this, "Unlocking "+uid);
		Long l = new Long(uid);
		completed(uid);
		synchronized(runningUIDs) {
			if(!runningUIDs.remove(l))
				throw new IllegalStateException("Could not unlock "+uid+"!");
		}
	}

	/**
	 * @return Some status information.
	 */
	public String getStatus() {
		StringBuffer sb = new StringBuffer();
		if (peers != null)
			sb.append(peers.getStatus());
		else
			sb.append("No peers yet");
		sb.append("\nInserts: ");
		synchronized(insertSenders) {
			int x = getNumInserts();
			sb.append(x);
			if((x < 5) && (x > 0)) {
				sb.append('\n');
				// Dump
				Iterator i = insertSenders.values().iterator();
				while(i.hasNext()) {
					AnyInsertSender s = (AnyInsertSender) i.next();
					sb.append(s.getUID());
					sb.append(": ");
					sb.append(s.getStatusString());
					sb.append('\n');
				}
			}
		}
		sb.append("\nRequests: ");
		sb.append(getNumRequests());
		sb.append("\nTransferring requests: ");
		sb.append(getNumTransferringRequests());
		sb.append('\n');
		return sb.toString();
	}

	/**
	 * @return TMCI peer list
	 */
	public String getTMCIPeerList() {
		StringBuffer sb = new StringBuffer();
		if (peers != null)
			sb.append(peers.getTMCIPeerList());
		else
			sb.append("No peers yet");
		return sb.toString();
	}
	
	public int getNumInserts() {
		synchronized(insertSenders) {
			return insertSenders.size();
		}
	}
	
	public int getNumRequests() {
		synchronized(requestSenders) {
			return requestSenders.size();
		}
	}

	public int getNumTransferringRequests() {
		synchronized(transferringRequestSenders) {
			return transferringRequestSenders.size();
		}
	}
	
	/**
	 * @return Data String for freeviz.
	 */
	public String getFreevizOutput() {
		StringBuffer sb = new StringBuffer();
		sb.append("\nrequests=");
		sb.append(getNumRequests());
		
		sb.append("\ntransferring_requests=");
		sb.append(getNumTransferringRequests());
		
		sb.append("\ninserts=");
		sb.append(getNumInserts());
		sb.append("\n");
		
		
		if (peers != null)
			sb.append(peers.getFreevizOutput());
		  						
		return sb.toString();
	}

	/**
	 * @return Our reference, compressed
	 */
	public byte[] myPublicRefCompressed() {
		SimpleFieldSet fs = exportPublicFieldSet();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DeflaterOutputStream gis;
		gis = new DeflaterOutputStream(baos);
		OutputStreamWriter osw = new OutputStreamWriter(gis);
		try {
			fs.writeTo(osw);
		} catch (IOException e) {
			throw new Error(e);
		}
		try {
			osw.flush();
			gis.close();
		} catch (IOException e1) {
			throw new Error(e1);
		}
		byte[] buf = baos.toByteArray();
		byte[] obuf = new byte[buf.length + 1];
		obuf[0] = 1;
		System.arraycopy(buf, 0, obuf, 1, buf.length);
		return obuf;
		// FIXME support compression when noderefs get big enough for it to be useful
	}

	final LRUQueue recentlyCompletedIDs;

	static final int MAX_RECENTLY_COMPLETED_IDS = 10*1000;

	/**
	 * Has a request completed with this ID recently?
	 */
	public synchronized boolean recentlyCompleted(long id) {
		return recentlyCompletedIDs.contains(new Long(id));
	}
	
	/**
	 * A request completed (regardless of success).
	 */
	public synchronized void completed(long id) {
		recentlyCompletedIDs.push(new Long(id));
		while(recentlyCompletedIDs.size() > MAX_RECENTLY_COMPLETED_IDS)
			recentlyCompletedIDs.pop();
	}

	/**
	 * Look up a cached public key by its hash.
	 */
	public DSAPublicKey getKey(byte[] hash) {
		ImmutableByteArrayWrapper w = new ImmutableByteArrayWrapper(hash);
		Logger.minor(this, "Getting pubkey: "+HexUtil.bytesToHex(hash));
		if(USE_RAM_PUBKEYS_CACHE) {
			synchronized(cachedPubKeys) {
				DSAPublicKey key = (DSAPublicKey) cachedPubKeys.get(w);
				if(key != null) {
					cachedPubKeys.push(w, key);
					Logger.minor(this, "Got "+HexUtil.bytesToHex(hash)+" from cache");
					return key;
				}
			}
		}
		try {
			DSAPublicKey key;
			key = pubKeyDatastore.fetchPubKey(hash, false);
			if(key == null)
				key = pubKeyDatacache.fetchPubKey(hash, false);
			if(key != null) {
				cacheKey(hash, key, false);
				Logger.minor(this, "Got "+HexUtil.bytesToHex(hash)+" from store");
			}
			return key;
		} catch (IOException e) {
			// FIXME deal with disk full, access perms etc; tell user about it.
			Logger.error(this, "Error accessing pubkey store: "+e, e);
			return null;
		}
	}
	
	/**
	 * Cache a public key
	 */
	public void cacheKey(byte[] hash, DSAPublicKey key, boolean deep) {
		Logger.minor(this, "Cache key: "+HexUtil.bytesToHex(hash)+" : "+key);
		ImmutableByteArrayWrapper w = new ImmutableByteArrayWrapper(hash);
		synchronized(cachedPubKeys) {
			DSAPublicKey key2 = (DSAPublicKey) cachedPubKeys.get(w);
			if((key2 != null) && !key2.equals(key)) {
				MessageDigest md256;
				// Check the hash.
				try {
					md256 = MessageDigest.getInstance("SHA-256");
				} catch (NoSuchAlgorithmException e) {
					throw new Error(e);
				}
				byte[] hashCheck = md256.digest(key.asBytes());
				if(Arrays.equals(hashCheck, hash)) {
					Logger.error(this, "Hash is correct!!!");
					// Verify the old key
					byte[] oldHash = md256.digest(key2.asBytes());
					if(Arrays.equals(oldHash, hash)) {
						Logger.error(this, "Old hash is correct too!! - Bug in DSAPublicKey.equals() or SHA-256 collision!");
					} else {
						Logger.error(this, "Old hash is wrong!");
						cachedPubKeys.removeKey(w);
						cacheKey(hash, key, deep);
					}
				} else {
					Logger.error(this, "New hash is wrong");
				}
				throw new IllegalArgumentException("Wrong hash?? Already have different key with same hash!");
			}
			cachedPubKeys.push(w, key);
			while(cachedPubKeys.size() > MAX_CACHED_KEYS)
				cachedPubKeys.popKey();
		}
		try {
			if(deep) {
				pubKeyDatastore.put(hash, key);
				pubKeyDatastore.fetchPubKey(hash, true);
			}
			pubKeyDatacache.put(hash, key);
			pubKeyDatacache.fetchPubKey(hash, true);
		} catch (IOException e) {
			// FIXME deal with disk full, access perms etc; tell user about it.
			Logger.error(this, "Error accessing pubkey store: "+e, e);
		}
	}

	public boolean isTestnetEnabled() {
		return testnetEnabled;
	}

	public ClientKeyBlock fetchKey(ClientKey key, boolean dontPromote) throws KeyVerifyException {
		if(key instanceof ClientCHK)
			return fetch((ClientCHK)key, dontPromote);
		else if(key instanceof ClientSSK)
			return fetch((ClientSSK)key, dontPromote);
		else
			throw new IllegalStateException("Don't know what to do with "+key);
	}

	private ClientKeyBlock fetch(ClientSSK clientSSK, boolean dontPromote) throws SSKVerifyException {
		DSAPublicKey key = clientSSK.getPubKey();
		if(key == null) {
			key = getKey(clientSSK.pubKeyHash);
		}
		if(key == null) return null;
		clientSSK.setPublicKey(key);
		SSKBlock block = fetch((NodeSSK)clientSSK.getNodeKey(), dontPromote);
		if(block == null) return null;
		// Move the pubkey to the top of the LRU, and fix it if it
		// was corrupt.
		cacheKey(clientSSK.pubKeyHash, key, false);
		return new ClientSSKBlock(block, clientSSK);
	}

	private ClientKeyBlock fetch(ClientCHK clientCHK, boolean dontPromote) throws CHKVerifyException {
		CHKBlock block = fetch(clientCHK.getNodeCHK(), dontPromote);
		if(block == null) return null;
		return new ClientCHKBlock(block, clientCHK);
	}
	
	public void exit(int reason) {
		try {
			this.park();
			System.out.println("Goodbye.");
			System.out.println(reason);
		} finally {
			System.exit(reason);
		}
	}
	
	public void exit(String reason){
		try {
			this.park();
			System.out.println("Goodbye. from "+this+" ("+reason+")");
		} finally {
			System.exit(0);
		}
	}
	
	/**
	 * Get the node into a state where it can be stopped safely
	 * May be called twice - once in exit (above) and then again
	 * from the wrapper triggered by calling System.exit(). Beware!
	 */
	public void park() {
		config.store();
	}

	public NodeUpdater getNodeUpdater(){
		return nodeUpdater;
	}
	
	public PeerNode[] getDarknetConnections() {
		return peers.myPeers;
	}
	
	public boolean addDarknetConnection(PeerNode pn) {
		boolean retval = peers.addPeer(pn);
		peers.writePeers();
		return retval;
	}
	
	public void removeDarknetConnection(PeerNode pn) {
		peers.disconnect(pn);
	}

	public void onConnectedPeer() {
		Logger.minor(this, "onConnectedPeer()");
		ipDetector.onConnectedPeer();
	}
	
	public String getBindTo(){
		return this.bindto;
	}
	
	public int getIdentityHash(){
		return Fields.hashCode(identityHash);
	}
	
	public synchronized boolean setNewestPeerLastGoodVersion( int version ) {
		if( version > buildOldAgeUserAlert.lastGoodVersion ) {
			if( buildOldAgeUserAlert.lastGoodVersion == 0 ) {
				clientCore.alerts.register(buildOldAgeUserAlert);
			}
			buildOldAgeUserAlert.lastGoodVersion = version;
			return true;
		}
		return false;
	}
	
	public double getBwlimitDelayTime() {
		return throttledPacketSendAverage.currentValue();
	}
	
	public double getNodeAveragePingTime() {
		return nodePinger.averagePingTime();
	}

	/**
	 * Add a ARKFetcher to the map
	 */
	public void addARKFetcher(String identity, ARKFetcher fetcher) {
		synchronized(arkFetchers) {
			if(arkFetchers.containsKey(identity)) {
				ARKFetcher af = (ARKFetcher) arkFetchers.get(identity);
				if(af != fetcher)
					Logger.error(this, "addARKFetcher(): identity '"+identity+"' already in arkFetcher as "+af+" and you want to add"+fetcher);
				else
					Logger.minor(this, "Re-adding "+identity+" : "+fetcher);
				return;
			}
			Logger.minor(this, "addARKFetcher(): adding ARK Fetcher for "+identity);
			arkFetchers.put(identity, fetcher);
		}
	}
	
	/**
	 * How many ARKFetchers are currently requesting ARKs?
	 */
	public int getNumARKFetchers() {
		return arkFetchers.size();
	}

	/**
	 * Remove a ARKFetcher from the map
	 */
	public void removeARKFetcher(String identity, ARKFetcher fetcher) {
		synchronized(arkFetchers) {
			if(!arkFetchers.containsKey(identity)) {
				Logger.error(this, "removeARKFetcher(): identity '"+identity+"' not in arkFetcher to remove");
				return;
			}
			Logger.minor(this, "removeARKFetcher(): removing ARK Fetcher for "+identity);
			ARKFetcher af = (ARKFetcher) arkFetchers.remove(identity);
			if(af != fetcher) {
				Logger.error(this, "Removed "+af+" should be "+fetcher+" for "+identity+" in removeARKFetcher");
			}
		}
	}

	/**
	 * Add a PeerNode status to the map
	 */
	public void addPeerNodeStatus(int pnStatus, PeerNode peerNode) {
		Integer peerNodeStatus = new Integer(pnStatus);
		HashSet statusSet = null;
		synchronized(peerNodeStatuses) {
			if(peerNodeStatuses.containsKey(peerNodeStatus)) {
				statusSet = (HashSet) peerNodeStatuses.get(peerNodeStatus);
				if(statusSet.contains(peerNode)) {
					Logger.error(this, "addPeerNodeStatus(): identity '"+peerNode.getIdentityString()+"' already in peerNodeStatuses as "+peerNode+" with status code "+peerNodeStatus);
					return;
				}
				peerNodeStatuses.remove(peerNodeStatus);
			} else {
				statusSet = new HashSet();
			}
			Logger.minor(this, "addPeerNodeStatus(): adding PeerNode for '"+peerNode.getIdentityString()+"' with status code "+peerNodeStatus);
			statusSet.add(peerNode);
			peerNodeStatuses.put(peerNodeStatus, statusSet);
		}
	}

	/**
	 * How many PeerNodes have a particular status?
	 */
	public int getPeerNodeStatusSize(int pnStatus) {
		Integer peerNodeStatus = new Integer(pnStatus);
		HashSet statusSet = null;
		synchronized(peerNodeStatuses) {
			if(peerNodeStatuses.containsKey(peerNodeStatus)) {
				statusSet = (HashSet) peerNodeStatuses.get(peerNodeStatus);
			} else {
				statusSet = new HashSet();
			}
			return statusSet.size();
		}
	}

	/**
	 * Remove a PeerNode status from the map
	 */
	public void removePeerNodeStatus(int pnStatus, PeerNode peerNode) {
		Integer peerNodeStatus = new Integer(pnStatus);
		HashSet statusSet = null;
		synchronized(peerNodeStatuses) {
			if(peerNodeStatuses.containsKey(peerNodeStatus)) {
				statusSet = (HashSet) peerNodeStatuses.get(peerNodeStatus);
				if(!statusSet.contains(peerNode)) {
					Logger.error(this, "removePeerNodeStatus(): identity '"+peerNode.getIdentityString()+"' not in peerNodeStatuses with status code "+peerNodeStatus);
					return;
				}
				peerNodeStatuses.remove(peerNodeStatus);
			} else {
				statusSet = new HashSet();
			}
			Logger.minor(this, "removePeerNodeStatus(): removing PeerNode for '"+peerNode.getIdentityString()+"' with status code "+peerNodeStatus);
			if(statusSet.contains(peerNode)) {
				statusSet.remove(peerNode);
			}
			peerNodeStatuses.put(peerNodeStatus, statusSet);
		}
	}

	/**
	 * Log the current PeerNode status summary if the timer has expired
	 */
	public void maybeLogPeerNodeStatusSummary(long now) {
	  if(now > nextPeerNodeStatusLogTime) {
		if((now - nextPeerNodeStatusLogTime) > (3*1000) && nextPeerNodeStatusLogTime > 0)
		  Logger.error(this,"maybeLogPeerNodeStatusSummary() not called for more than 3 seconds ("+(now - nextPeerNodeStatusLogTime)+").  PacketSender getting bogged down or something?");
		int numberOfConnected = getPeerNodeStatusSize(PEER_NODE_STATUS_CONNECTED);
		int numberOfRoutingBackedOff = getPeerNodeStatusSize(PEER_NODE_STATUS_ROUTING_BACKED_OFF);
		int numberOfTooNew = getPeerNodeStatusSize(PEER_NODE_STATUS_TOO_NEW);
		int numberOfTooOld = getPeerNodeStatusSize(PEER_NODE_STATUS_TOO_OLD);
		int numberOfDisconnected = getPeerNodeStatusSize(PEER_NODE_STATUS_DISCONNECTED);
		int numberOfNeverConnected = getPeerNodeStatusSize(PEER_NODE_STATUS_NEVER_CONNECTED);
		int numberOfDisabled = getPeerNodeStatusSize(PEER_NODE_STATUS_DISABLED);
		int numberOfListenOnly = getPeerNodeStatusSize(PEER_NODE_STATUS_LISTEN_ONLY);
		int numberOfListening = getPeerNodeStatusSize(PEER_NODE_STATUS_LISTENING);
		int numberOfBursting = getPeerNodeStatusSize(PEER_NODE_STATUS_BURSTING);
		Logger.normal(this, "Connected: "+numberOfConnected+"  Routing Backed Off: "+numberOfRoutingBackedOff+"  Too New: "+numberOfTooNew+"  Too Old: "+numberOfTooOld+"  Disconnected: "+numberOfDisconnected+"  Never Connected: "+numberOfNeverConnected+"  Disabled: "+numberOfDisabled+"  Bursting: "+numberOfBursting+"  Listening: "+numberOfListening+"  Listen Only: "+numberOfListenOnly);
		nextPeerNodeStatusLogTime = now + peerNodeStatusLogInterval;
	  }
	}

	/**
	 * Update oldestNeverConnectedPeerAge if the timer has expired
	 */
	public void maybeUpdateOldestNeverConnectedPeerAge(long now) {
	  if(now > nextOldestNeverConnectedPeerAgeUpdateTime) {
		oldestNeverConnectedPeerAge = 0;
	   	if(peers != null) {
		  PeerNode[] peerList = peers.myPeers;
		  for(int i=0;i<peerList.length;i++) {
			PeerNode pn = peerList[i];
			if(pn.getPeerNodeStatus() == PEER_NODE_STATUS_NEVER_CONNECTED) {
			  if((now - pn.getPeerAddedTime()) > oldestNeverConnectedPeerAge) {
				oldestNeverConnectedPeerAge = now - pn.getPeerAddedTime();
			  }
			}
		  }
	   	}
	   	if(oldestNeverConnectedPeerAge > 0)
		  Logger.minor(this, "Oldest never connected peer is "+oldestNeverConnectedPeerAge+"ms old");
		nextOldestNeverConnectedPeerAgeUpdateTime = now + oldestNeverConnectedPeerAgeUpdateInterval;
	  }
	}

	public long getOldestNeverConnectedPeerAge() {
	  return oldestNeverConnectedPeerAge;
	}

	/**
	 * Handle a received node to node text message
	 */
	public void receivedNodeToNodeTextMessage(Message m) {
	  PeerNode source = (PeerNode)m.getSource();
	  int type = ((Integer) m.getObject(DMT.NODE_TO_NODE_MESSAGE_TYPE)).intValue();
	  if(type == Node.N2N_TEXT_MESSAGE_TYPE_USERALERT) {
		String source_nodename = (String) m.getObject(DMT.SOURCE_NODENAME);
		String target_nodename = (String) m.getObject(DMT.TARGET_NODENAME);
		String text = (String) m.getObject(DMT.NODE_TO_NODE_MESSAGE_TEXT);
		Logger.normal(this, "Received N2NTM from '"+source_nodename+"' to '"+target_nodename+"': "+text);
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("type", Integer.toString(type));
		fs.put("source_nodename", Base64.encode(source_nodename.getBytes()));
		fs.put("target_nodename", Base64.encode(target_nodename.getBytes()));
		fs.put("text", Base64.encode(text.getBytes()));
		int fileNumber = source.writeNewExtraPeerDataFile( fs, EXTRA_PEER_DATA_TYPE_N2NTM);
		if( fileNumber == -1 ) {
			Logger.error( this, "Failed to write N2NTM to extra peer data file for peer "+source.getPeer());
		}
		// Keep track of the fileNumber so we can potentially delete the extra peer data file later, the file is authoritative
		handleNodeToNodeTextMessageSimpleFieldSet(fs, source, fileNumber);
	  } else {
		Logger.error(this, "Received unknown node to node message type '"+type+"' from "+source.getPeer());
	  }
	}

	/**
	 * Handle a node to node text message SimpleFieldSet
	 */
	public void handleNodeToNodeTextMessageSimpleFieldSet(SimpleFieldSet fs, PeerNode source, int fileNumber) {
	  int type = new Integer(fs.get("type")).intValue();
	  if(type == Node.N2N_TEXT_MESSAGE_TYPE_USERALERT) {
		String source_nodename = null;
		String target_nodename = null;
		String text = null;
	  	try {
			source_nodename = new String(Base64.decode(fs.get("source_nodename")));
			target_nodename = new String(Base64.decode(fs.get("target_nodename")));
			text = new String(Base64.decode(fs.get("text")));
		} catch (IllegalBase64Exception e) {
			Logger.error(this, "Bad Base64 encoding when decoding a N2NTM SimpleFieldSet", e);
			return;
		}
		N2NTMUserAlert userAlert = new N2NTMUserAlert(source, source_nodename, target_nodename, text, fileNumber);
			clientCore.alerts.register(userAlert);
	  } else {
		Logger.error(this, "Received unknown node to node message type '"+type+"' from "+source.getPeer());
	  }
	}

	public String getMyName() {
	  return myName;
	}

	public UdpSocketManager getUSM() {
	  return usm;
	}

	public int getNetworkSizeEstimate(int numberOfMinutes) {
	  return lm.getNetworkSizeEstimate( numberOfMinutes );
	}

	/**
	 * Add a PeerNode routing backoff reason to the map
	 */
	public void addPeerNodeRoutingBackoffReason(String peerNodeRoutingBackoffReason, PeerNode peerNode) {
		synchronized(peerNodeRoutingBackoffReasons) {
			HashSet reasonSet = null;
			if(peerNodeRoutingBackoffReasons.containsKey(peerNodeRoutingBackoffReason)) {
				reasonSet = (HashSet) peerNodeRoutingBackoffReasons.get(peerNodeRoutingBackoffReason);
				if(reasonSet.contains(peerNode)) {
					Logger.error(this, "addPeerNodeRoutingBackoffReason(): identity '"+peerNode.getIdentityString()+"' already in peerNodeRoutingBackoffReasons as "+peerNode+" with status code "+peerNodeRoutingBackoffReason);
					return;
				}
				peerNodeRoutingBackoffReasons.remove(peerNodeRoutingBackoffReason);
			} else {
				reasonSet = new HashSet();
			}
			Logger.minor(this, "addPeerNodeRoutingBackoffReason(): adding PeerNode for '"+peerNode.getIdentityString()+"' with status code "+peerNodeRoutingBackoffReason);
			reasonSet.add(peerNode);
			peerNodeRoutingBackoffReasons.put(peerNodeRoutingBackoffReason, reasonSet);
		}
	}
	
	/**
	 * What are the currently tracked PeerNode routing backoff reasons?
	 */
	public String [] getPeerNodeRoutingBackoffReasons() {
		String [] reasonStrings;
		synchronized(peerNodeRoutingBackoffReasons) {
			reasonStrings = (String []) peerNodeRoutingBackoffReasons.keySet().toArray(new String[peerNodeRoutingBackoffReasons.size()]);
		}
		Arrays.sort(reasonStrings);
		return reasonStrings;
	}
	
	/**
	 * How many PeerNodes have a particular routing backoff reason?
	 */
	public int getPeerNodeRoutingBackoffReasonSize(String peerNodeRoutingBackoffReason) {
		HashSet reasonSet = null;
		synchronized(peerNodeRoutingBackoffReasons) {
			if(peerNodeRoutingBackoffReasons.containsKey(peerNodeRoutingBackoffReason)) {
				reasonSet = (HashSet) peerNodeRoutingBackoffReasons.get(peerNodeRoutingBackoffReason);
				return reasonSet.size();
			} else {
				return 0;
			}
		}
	}

	/**
	 * Remove a PeerNode routing backoff reason from the map
	 */
	public void removePeerNodeRoutingBackoffReason(String peerNodeRoutingBackoffReason, PeerNode peerNode) {
		HashSet reasonSet = null;
		synchronized(peerNodeRoutingBackoffReasons) {
			if(peerNodeRoutingBackoffReasons.containsKey(peerNodeRoutingBackoffReason)) {
				reasonSet = (HashSet) peerNodeRoutingBackoffReasons.get(peerNodeRoutingBackoffReason);
				if(!reasonSet.contains(peerNode)) {
					Logger.error(this, "removePeerNodeRoutingBackoffReason(): identity '"+peerNode.getIdentityString()+"' not in peerNodeRoutingBackoffReasons with status code "+peerNodeRoutingBackoffReason);
					return;
				}
				peerNodeRoutingBackoffReasons.remove(peerNodeRoutingBackoffReason);
			} else {
				reasonSet = new HashSet();
			}
			Logger.minor(this, "removePeerNodeRoutingBackoffReason(): removing PeerNode for '"+peerNode.getIdentityString()+"' with status code "+peerNodeRoutingBackoffReason);
			if(reasonSet.contains(peerNode)) {
				reasonSet.remove(peerNode);
			}
			if(reasonSet.size() > 0) {
				peerNodeRoutingBackoffReasons.put(peerNodeRoutingBackoffReason, reasonSet);
			}
		}
	}

	/**
	 * Start a ready ARKFetcher if the timer has expired
	 */
	public void maybeStartAReadyARKFetcher(long now) {
	  if(now > nextReadyARKFetcherStartTime) {
	  	if(arkFetchManager.hasReadyARKFetchers()) {
			if(getNumARKFetchers() >= 30) {
				Logger.error(this, "Not starting ARKFetcher in maybeStartAReadyARKFetcher() because there are already 30 or more ARK Fetchers running");
			} else {
				arkFetchManager.maybeStartNextReadyARKFetcher();
			}
		}
		nextReadyARKFetcherStartTime = now + readyARKFetcherStartInterval;
	  }
	}

	/**
	 * Update peerManagerUserAlertStats if the timer has expired
	 */
	public void maybeUpdatePeerManagerUserAlertStats(long now) {
		if(now > nextPeerManagerUserAlertStatsUpdateTime) {
			if(getBwlimitDelayTime() > MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD) {
				if(firstBwlimitDelayTimeThresholdBreak == 0) {
					firstBwlimitDelayTimeThresholdBreak = now;
				}
			} else {
				firstBwlimitDelayTimeThresholdBreak = 0;
			}
			if((firstBwlimitDelayTimeThresholdBreak != 0) && ((now - firstBwlimitDelayTimeThresholdBreak) >= MAX_BWLIMIT_DELAY_TIME_ALERT_DELAY)) {
				bwlimitDelayAlertRelevant = true;
			} else {
				bwlimitDelayAlertRelevant = false;
			}
			if(getNodeAveragePingTime() > MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD) {
				if(firstNodeAveragePingTimeThresholdBreak == 0) {
					firstNodeAveragePingTimeThresholdBreak = now;
				}
			} else {
				firstNodeAveragePingTimeThresholdBreak = 0;
			}
			if((firstNodeAveragePingTimeThresholdBreak != 0) && ((now - firstNodeAveragePingTimeThresholdBreak) >= MAX_NODE_AVERAGE_PING_TIME_ALERT_DELAY)) {
				nodeAveragePingAlertRelevant = true;
			} else {
				nodeAveragePingAlertRelevant = false;
			}
			Logger.debug(this, "mUPMUAS: "+now+": "+getBwlimitDelayTime()+" >? "+MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD+" since "+firstBwlimitDelayTimeThresholdBreak+" ("+bwlimitDelayAlertRelevant+") "+getNodeAveragePingTime()+" >? "+MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD+" since "+firstNodeAveragePingTimeThresholdBreak+" ("+nodeAveragePingAlertRelevant+")");
			nextPeerManagerUserAlertStatsUpdateTime = now + peerManagerUserAlertStatsUpdateInterval;
		}
	}

	public PeerNode[] getPeerNodes() {
		PeerManager pm = peers;
		return pm.myPeers;
	}
	
	public PeerNodeStatus[] getPeerNodeStatuses() {
		PeerNodeStatus[] peerNodeStatuses = new PeerNodeStatus[peers.myPeers.length];
		for (int peerIndex = 0, peerCount = peers.myPeers.length; peerIndex < peerCount; peerIndex++) {
			peerNodeStatuses[peerIndex] = peers.myPeers[peerIndex].getStatus();
		}
		return peerNodeStatuses;
	}

	/**
	 * Return a peer of the node given its ip and port, name or identity, as a String
	 */
	public PeerNode getPeerNode(String nodeIdentifier) {
		PeerNode[] pn = peers.myPeers;
		for(int i=0;i<pn.length;i++)
		{
			Peer peer = pn[i].getDetectedPeer();
			String nodeIpAndPort = "";
			if(peer != null) {
				nodeIpAndPort = peer.toString();
			}
			String name = pn[i].myName;
			String identity = pn[i].getIdentityString();
			if(identity.equals(nodeIdentifier) || nodeIpAndPort.equals(nodeIdentifier) || name.equals(nodeIdentifier))
			{
				return pn[i];
			}
		}
		return null;
	}

	public boolean isHasStarted() {
		return hasStarted;
	}

	public void queueRandomReinsert(KeyBlock block) {
		clientCore.queueRandomReinsert(block);
	}

	public double pRejectIncomingInstantly() {
		return pInstantRejectIncoming.currentValue();
	}
	
	public String getExtraPeerDataDir() {
		return extraPeerDataDir.getPath();
	}

	public boolean noConnectedPeers() {
		return !peers.anyConnectedPeers();
	}

	public double getLocation() {
		return lm.loc.getValue();
	}

	public boolean isAdvancedDarknetEnabled() {
		return clientCore.isAdvancedDarknetEnabled();
	}
	
	// FIXME convert these kind of threads to Checkpointed's and implement a handler
	// using the PacketSender/Ticker. Would save a few threads.
	
	class ThrottlePersister implements Runnable {

		void interrupt() {
			synchronized(this) {
				notify();
			}
		}
		
		public void run() {
			while(true) {
				try {
					persistThrottle();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t, t);
				}
				try {
					synchronized(this) {
						wait(60*1000);
					}
				} catch (InterruptedException e) {
					// Maybe it's time to wake up?
				}
			}
		}
		
	}

	public void persistThrottle() {
		SimpleFieldSet fs = persistThrottlesToFieldSet();
		try {
			FileOutputStream fos = new FileOutputStream(persistTemp);
			// FIXME common pattern, reuse it.
			BufferedOutputStream bos = new BufferedOutputStream(fos);
			OutputStreamWriter osw = new OutputStreamWriter(bos, "UTF-8");
			try {
				fs.writeTo(osw);
			} catch (IOException e) {
				try {
					fos.close();
					persistTemp.delete();
					return;
				} catch (IOException e1) {
					// Ignore
				}
			}
			try {
				osw.close();
			} catch (IOException e) {
				// Huh?
				Logger.error(this, "Caught while closing: "+e, e);
				return;
			}
			// Try an atomic rename
			if(!persistTemp.renameTo(persistTarget)) {
				// Not supported on some systems (Windows)
				if(!persistTarget.delete()) {
					if(persistTarget.exists()) {
						Logger.error(this, "Could not delete "+persistTarget+" - check permissions");
					}
				}
				if(!persistTemp.renameTo(persistTarget)) {
					Logger.error(this, "Could not rename "+persistTemp+" to "+persistTarget+" - check permissions");
				}
			}
		} catch (FileNotFoundException e) {
			Logger.error(this, "Could not store throttle data to disk: "+e, e);
			return;
		} catch (UnsupportedEncodingException e) {
			Logger.error(this, "Unsupported encoding: UTF-8 !!!!: "+e, e);
		}
		
	}

	private SimpleFieldSet persistThrottlesToFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		fs.put("RequestStarters", clientCore.requestStarters.persistToFieldSet());
		fs.put("RemoteChkFetchBytesSentAverage", remoteChkFetchBytesSentAverage.exportFieldSet());
		fs.put("RemoteSskFetchBytesSentAverage", remoteSskFetchBytesSentAverage.exportFieldSet());
		fs.put("RemoteChkInsertBytesSentAverage", remoteChkInsertBytesSentAverage.exportFieldSet());
		fs.put("RemoteSskInsertBytesSentAverage", remoteSskInsertBytesSentAverage.exportFieldSet());
		fs.put("RemoteChkFetchBytesReceivedAverage", remoteChkFetchBytesReceivedAverage.exportFieldSet());
		fs.put("RemoteSskFetchBytesReceivedAverage", remoteSskFetchBytesReceivedAverage.exportFieldSet());
		fs.put("RemoteChkInsertBytesReceivedAverage", remoteChkInsertBytesReceivedAverage.exportFieldSet());
		fs.put("RemoteSskInsertBytesReceivedAverage", remoteSskInsertBytesReceivedAverage.exportFieldSet());
		fs.put("LocalChkFetchBytesSentAverage", localChkFetchBytesSentAverage.exportFieldSet());
		fs.put("LocalSskFetchBytesSentAverage", localSskFetchBytesSentAverage.exportFieldSet());
		fs.put("LocalChkInsertBytesSentAverage", localChkInsertBytesSentAverage.exportFieldSet());
		fs.put("LocalSskInsertBytesSentAverage", localSskInsertBytesSentAverage.exportFieldSet());
		fs.put("LocalChkFetchBytesReceivedAverage", localChkFetchBytesReceivedAverage.exportFieldSet());
		fs.put("LocalSskFetchBytesReceivedAverage", localSskFetchBytesReceivedAverage.exportFieldSet());
		fs.put("LocalChkInsertBytesReceivedAverage", localChkInsertBytesReceivedAverage.exportFieldSet());
		fs.put("LocalSskInsertBytesReceivedAverage", localSskInsertBytesReceivedAverage.exportFieldSet());

		// FIXME persist the rest
		return fs;
	}

}
