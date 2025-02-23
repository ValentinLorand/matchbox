package ch.ahdis.matchbox.engine;

import java.io.IOException;
import java.net.URISyntaxException;

import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.validation.IgLoader;
import org.hl7.fhir.validation.ValidationEngine;

import ch.ahdis.matchbox.engine.cli.VersionUtil;

/*
 * #%L
 * Matchbox Engine
 * %%
 * Copyright (C) 2022 ahdis
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

/**
 * Mapping Engine to convert between CDA and FHIR, based on the HL7 FHIR Mapping
 * Language, HL7 Java validator reference implementation and matchbox.health
 * 
 * @author oliveregger, ahdis ag
 *
 */
public class CdaMappingEngine extends MatchboxEngine {

	protected static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CdaMappingEngine.class);

	/** 
	 * creates another instance of a mapping engine. use this if you have different versions of packages
	 * or conformance resources 
	 * 
	 * @param other base instance to be used
	 * @throws FHIRException FHIR Exception
	 * @throws IOException IO Exception
	 */
	public CdaMappingEngine(ValidationEngine other) throws FHIRException, IOException {
		super(other);
	}
  /**
   * Builder class to instantiate a CdaMappingEngine
   * @author oliveregger, ahdis ag
   *
   */
	public static class CdaMappingEngineBuilder extends ValidationEngineBuilder {
		
		
		/**
		 * Creates an empty builder instance
		 */
		public CdaMappingEngineBuilder() {
		}

		/**
		 * Create a CDA mapping engine based on the forked CDA Model (see https://github.com/ahdis/cda-core-2.0/tree/lab)
		 * 
		 * @return MappingEngine which allows to convert between CDA and FHIR R4
		 * @throws FHIRException FHIR Exception
		 * @throws IOException IO Exception
		 * @throws URISyntaxException
		 */
		public CdaMappingEngine getEngine() throws FHIRException, IOException, URISyntaxException {
			log.info("Initializing CDA Mapping Engine");
			log.info(VersionUtil.getPoweredBy());
			CdaMappingEngine engine = new CdaMappingEngine(super.fromNothing());
			// if the version would have been set before (constructor) the package is loaded
			// from the package cache, we don't want this
			engine.setVersion("4.0.1");
			IgLoader igLoader = new IgLoaderFromClassPath(engine.getPcm(), engine.getContext(), engine.getVersion(),
					engine.isDebug());
			engine.setIgLoader(igLoader);
			igLoader.loadIg(engine.getIgs(), null, "/hl7.fhir.r4.core.tgz", false);
			igLoader.loadIg(engine.getIgs(), null, "/hl7.terminology#5.0.0.tgz", false);
			igLoader.loadIg(engine.getIgs(), null, "/cda-core-2.0#2.1.0-cibuild.tgz", false);
			engine.getContext().setCanRunWithoutTerminology(true);
			engine.getContext().setNoTerminologyServer(true);
			engine.getContext().setPackageTracker(engine);

			return engine;
		}

		/**
		 * Create a CDA mapping engine with conformance resources provided as an FHIR Implementation Guide on the classpath 
		 * 
		 * @param mapIg path to FHIR Implementation Guide (IG) in NPM format which is on
		 *              the classpath and contains the required conformance resources
		 *              for mapping (StructureMap, CodeSystem, ValueSet, CoceptMaps etc)
		 * @return MappingEngine configured with maps for transformation
		 * @throws FHIRException FHIR Exception
		 * @throws IOException IO Exception
		 * @throws URISyntaxException
		 */
		public CdaMappingEngine getEngine(String mapIg) throws FHIRException, IOException, URISyntaxException {
			CdaMappingEngine engine = this.getEngine();
			engine.getIgLoader().loadIg(engine.getIgs(), null, mapIg, false);
			return engine;
		}

	}

	/**
	 * Transforms a CDA with the map identified by the uri to the output defined by
	 * the map
	 * 
	 * @param cda    content of the CDA xml
	 * @param mapUri canonical url of StructureMap to use for the transformation
	 * @return Bundle as output from the conversion
	 * @throws FHIRException FHIR Exception
	 * @throws IOException IO Exception
	 */
	public Bundle transformCdaToFhir(String cda, String mapUri) throws FHIRException, IOException {
		return (Bundle) transformToFhir(cda, false, mapUri);
	}

}
