package ch.ahdis.matchbox;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Locale;

import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.MetadataResource;
import org.hl7.fhir.r5.conformance.R5ExtensionsLoader;
import org.hl7.fhir.r5.terminologies.CodeSystemUtilities;
import org.hl7.fhir.utilities.FhirPublication;
import org.hl7.fhir.utilities.TimeTracker;
import org.hl7.fhir.utilities.VersionUtilities;
import org.hl7.fhir.validation.IgLoader;
import org.hl7.fhir.validation.cli.services.IPackageInstaller;
import org.hl7.fhir.validation.cli.services.SessionCache;
import org.hl7.fhir.validation.cli.services.StandAloneValidatorFetcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.binary.api.IBinaryStorageSvc;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionDao;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionResourceDao;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionResourceEntity;
import ca.uhn.fhir.jpa.packages.IHapiPackageCacheManager;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.MatchboxEngine.MatchboxEngineBuilder;


public class MatchboxEngineSupport {
	
	protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MatchboxEngineSupport.class);

	private static MatchboxEngine engine = null;
	private SessionCache sessionCache;
	
	private boolean initialized = false;

	@Autowired
	private DaoRegistry myDaoRegistry;
  
	
	@Autowired
	private INpmPackageVersionResourceDao myPackageVersionResourceDao;
	
	@Autowired
	private PlatformTransactionManager myTxManager;
	
	@Autowired
	private IHapiPackageCacheManager myPackageCacheManager;

	@Autowired
	private INpmPackageVersionDao myNpmPackageVersionDao;
	
	@Autowired(required = false)
	private IBinaryStorageSvc myBinaryStorageSvc;

	@Autowired
	private CliContext cliContext;

	public MatchboxEngineSupport() {
		this.sessionCache = new SessionCache();
	}

	// Note this assumes that the canonical and ig version are the same if the version is specified 
	public NpmPackageVersionResourceEntity loadPackageAssetByUrl(String theCanonicalUrl) {
		NpmPackageVersionResourceEntity resourceEntity  = new TransactionTemplate(myTxManager).execute(tx -> {
			String canonicalUrl = theCanonicalUrl;
			int versionSeparator = canonicalUrl.lastIndexOf('|');
			Slice<NpmPackageVersionResourceEntity> slice;
			if (versionSeparator != -1) {
				String canonicalVersion = canonicalUrl.substring(versionSeparator + 1);
				canonicalUrl = canonicalUrl.substring(0, versionSeparator);
				slice = myPackageVersionResourceDao.findByCanonicalUrlByCanonicalVersion(PageRequest.of(0, 2), canonicalUrl, canonicalVersion);
			} else {
				slice = myPackageVersionResourceDao.findCurrentByCanonicalUrl(PageRequest.of(0, 2), canonicalUrl);
			}
			if (slice.isEmpty()) {
				return null;
			} 
			if (slice.getContent().size()>1) {
				log.error("multiple entries with same canonical (version) for "+theCanonicalUrl);
			}
			return slice.getContent().get(0);
		});
		return resourceEntity;
	}

	public NpmPackageVersionResourceEntity loadPackageAssetByLikeCanonicalCurrent(String canonical) {
		if (!canonical.contains("|")) {
			if (canonical.contains("/")) {
				// remove resource id
				canonical = canonical.substring(0, canonical.lastIndexOf("/"));
				if (canonical.contains("/")) {
						// remove resource name
					String canonicalUrlLike = canonical.substring(0, canonical.lastIndexOf("/")) +"*";
					NpmPackageVersionResourceEntity resourceEntity  = new TransactionTemplate(myTxManager).execute(tx -> {
						Slice<NpmPackageVersionResourceEntity> slice = myPackageVersionResourceDao.findCurrentVersionByLikeCanonicalUrl(PageRequest.of(0, 1), canonicalUrlLike);
						if (slice.isEmpty()) {
							return null;
						} 
						return slice.getContent().get(0);
					});
					return resourceEntity;
				}
			}
		}
		return null;
	}

	/**
	 * returns a cached resource stored in the session cache
	 * @param resource
	 * @param id
	 * @return
	 */
	public MetadataResource getCachedResource(String resource, String id) {
		for (String sessionId : sessionCache.getSessionIds()) {
			MatchboxEngine engine = (MatchboxEngine) sessionCache.fetchSessionValidatorEngine(sessionId);
			org.hl7.fhir.r4.model.Resource res = engine.getCanonicalResourceById(resource, id);
			if (res != null) {
				return (MetadataResource) res;
			}
		}
		return null;
	}
			
	/**
	 * returns a matchbox-engine for the specified canonical with cliClontext parameters
	 * @param canonical URL to validate
	 * @param cliContext cliContext parameters
	 * @param create if true, create a new engine
	 * @param reload if true, reload the engine
	 * @return matchbox-engine
	 * @throws IOException 
	 */
	public synchronized MatchboxEngine getMatchboxEngine(String canonical, CliContext cliContext, boolean create, boolean reload) {
		TimeTracker tt = new TimeTracker();

		while (!this.isInitialized()) {
			log.info("ValidationEngine is not yet initialized, waiting for initialization of packages");
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				log.error("error waiting for initialization", e);
			}
		}		
		if (reload) {
			engine = null;
			if ("default".endsWith(canonical)){
				this.sessionCache = new SessionCache();
			}
			this.setInitialized(false);
		}
		if (cliContext == null) {
			cliContext = new CliContext(this.cliContext);
		}
		if (engine == null) {
				try {
					engine = new MatchboxEngineBuilder().getEngine();
				} catch (FHIRException e) {
					log.error("error generating matchbox engine", e);
				} catch (IOException e) {
					log.error("error generating matchbox engine", e);
				} catch (URISyntaxException e) {
					log.error("error generating matchbox engine", e);
				}
				
					IgLoader igLoader = null;
					try {
						igLoader = new IgLoaderFromJpaPackageCache(engine.getPcm(), engine.getContext(), engine.getVersion(),
								engine.isDebug(), myPackageCacheManager, myNpmPackageVersionDao, myDaoRegistry, myBinaryStorageSvc, myTxManager);
						
					} catch (IOException e) {
						log.error("error generating matchbox engine", e);
					}
					engine.setIgLoader(igLoader);
					try {
						// FIXME if we want to validate against different version we would probably no need to load theses packages
						engine.loadPackage("hl7.terminology",  "5.0.0");
						engine.loadPackage("hl7.fhir.r4.core",  "4.0.1");
					} catch (FHIRException | IOException e) {
						log.error("error connecting to terminology server ");
						return null;
					}
		}

		String ig = cliContext.getIg();
		if (ig == null) {
			if ("default".equals(canonical) || canonical == null || engine.getCanonicalResource(canonical)!=null) {
				ig = "hl7.fhir.r4.core#4.0.1";
			} else {
				NpmPackageVersionResourceEntity  npm = loadPackageAssetByUrl(canonical);
				if (npm==null) {
					// lets try the special case where canonical should be created and there is already a package which uses this canonical in an ig which is current
					npm = loadPackageAssetByLikeCanonicalCurrent(canonical);
				}
				if (npm != null) {
					ig = npm.getPackageVersion().getPackageId()+"#"+npm.getPackageVersion().getVersionId();
					log.info("using ig "+ig+" for canonical url "+canonical);
					cliContext.setIg(ig); // set the ig in the cliContext that hashCode will be set
				} 
			}
		}

		if (reload) {
			this.setInitialized(true);
		}

		// check if we have already a validator in cache for that
		MatchboxEngine matchboxEngine = (MatchboxEngine) this.sessionCache.fetchSessionValidatorEngine(""+cliContext.hashCode());
		if (matchboxEngine!=null && reload == false) {
			log.info("using cached validate engine" +(ig!=null ? "for "+ig : "" ) +" with parameters "+cliContext.hashCode());
			return matchboxEngine;
		}
		
		// create a new validator
		if (create) {
			log.info("creating new validate engine for "+(ig!=null ? "for "+ig : "" ) +" with parameters "+cliContext.hashCode());
			try {
				matchboxEngine = new MatchboxEngine(engine);
				MatchboxEngine validator = matchboxEngine;

				// FIXME we need to figure out h
				// if (!VersionUtilities.isR5Ver(validator.getContext().getVersion())) {
				// 	log.info("  Load R5 Extensions");
				// 	R5ExtensionsLoader r5e = new R5ExtensionsLoader(validator.getPcm(), validator.getContext());
				// 	r5e.load();
				// 	r5e.loadR5Extensions();
				// 	log.info(" - " + r5e.getCount() + " resources (" + tt.milestone() + ")");
				// }
				log.info("  Terminology server " + cliContext.getTxServer());
				String txServer = cliContext.getTxServer();
				if ("n/a".equals(cliContext.getTxServer())) {
					txServer = null;
					validator.getContext().setCanRunWithoutTerminology(true);
					validator.getContext().setNoTerminologyServer(true);
				} else {
					// we need really to do it explicitly
					validator.getContext().setCanRunWithoutTerminology(false);
					validator.getContext().setNoTerminologyServer(false);
				}
				String txver = validator.setTerminologyServer(txServer, null, FhirPublication.R4);
				log.info(" - Version " + txver + " (" + tt.milestone() + ")");

				validator.setDebug(cliContext.isDoDebug());
				validator.getContext().setLogger(new EngineLoggingService(cliContext.isDoDebug()));

				IgLoaderFromJpaPackageCache igLoader = new IgLoaderFromJpaPackageCache(validator.getPcm(), validator.getContext(), validator.getVersion(),
				validator.isDebug(), myPackageCacheManager, myNpmPackageVersionDao, myDaoRegistry, myBinaryStorageSvc, myTxManager);
				validator.setIgLoader(igLoader);
				if (ig!=null) {	
					validator.getIgLoader().loadIg(validator.getIgs(), validator.getBinaries(), ig, true);
				}

				log.info("  Package Summary: "+validator.getContext().loadedPackageSummary());

				validator.setQuestionnaireMode(cliContext.getQuestionnaireMode());
				validator.setLevel(cliContext.getLevel());
				validator.setDoNative(cliContext.isDoNative());
				validator.setHintAboutNonMustSupport(cliContext.isHintAboutNonMustSupport());
	//			for (String s : cliContext.getExtensions()) {
	//			if ("any".equals(s)) {
					validator.setAnyExtensionsAllowed(true);
	//			} else {          
	//				validator.getExtensionDomains().add(s);
	//			}
	//			}
				validator.setLanguage(cliContext.getLang());
				validator.setLocale(Locale.forLanguageTag(cliContext.getLocale()));
				validator.setSnomedExtension(cliContext.getSnomedCTCode());
				validator.setAssumeValidRestReferences(cliContext.isAssumeValidRestReferences());
				validator.setShowMessagesFromReferences(cliContext.isShowMessagesFromReferences());
				validator.setDoImplicitFHIRPathStringConversion(cliContext.isDoImplicitFHIRPathStringConversion());
				validator.setHtmlInMarkdownCheck(cliContext.getHtmlInMarkdownCheck());
				validator.setNoExtensibleBindingMessages(cliContext.isNoExtensibleBindingMessages());
				validator.setNoUnicodeBiDiControlChars(cliContext.isNoUnicodeBiDiControlChars());
				validator.setNoInvariantChecks(cliContext.isNoInvariants());
				validator.setWantInvariantInMessage(cliContext.isWantInvariantsInMessages());
				validator.setSecurityChecks(cliContext.isSecurityChecks());
				validator.setCrumbTrails(cliContext.isCrumbTrails());
				validator.setForPublication(cliContext.isForPublication());
				validator.setShowTimes(true);
				validator.setAllowExampleUrls(cliContext.isAllowExampleUrls());
				StandAloneValidatorFetcher fetcher = new StandAloneValidatorFetcher(validator.getPcm(), validator.getContext(), new IPackageInstaller()  {
					// https://github.com/ahdis/matchbox/issues/67
					@Override
					public boolean packageExists(String id, String ver) throws IOException, FHIRException {
					  return false;
					}
			
					@Override
					public void loadPackage(String id, String ver) throws IOException, FHIRException {
					}}
				  );
				validator.setFetcher(fetcher);
				validator.getContext().setLocator(fetcher);
	//			validator.getBundleValidationRules().addAll(cliContext.getBundleValidationRules());
				validator.setJurisdiction(CodeSystemUtilities.readCoding(cliContext.getJurisdiction()));
	//			TerminologyCache.setNoCaching(cliContext.isNoInternalCaching());
				sessionCache.cacheSession(""+cliContext.hashCode(), validator);

				return validator;
			} catch (FHIRException e) {
				log.error("Error loading validator: "+e.getMessage(), e);
			} catch (IOException e) {
				log.error("Error loading validator: "+e.getMessage(), e);
			} catch (URISyntaxException e) {
				log.error("Error loading validator: "+e.getMessage(), e);
			}
		}
		return null;
	}

	public boolean isInitialized() {
		return initialized;
	}

	public void setInitialized(boolean initialized) {
		this.initialized = initialized;
	}
	

}
