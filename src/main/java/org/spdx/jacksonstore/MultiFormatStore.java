/**
 * Copyright (c) 2020 Source Auditor Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.spdx.jacksonstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.SpdxConstants;
import org.spdx.storage.ISerializableModelStore;
import org.spdx.storage.simple.InMemSpdxStore;
import org.spdx.storage.simple.StoredTypedItem;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Model store that supports multiple serialization formats (JSON, XML, YAML)
 * 
 * Note that the serialization/deserlization methods are synchronized to prevent the format or verbose changing while serilizing
 * 
 * @author Gary O'Neall
 *
 */
public class MultiFormatStore extends InMemSpdxStore implements ISerializableModelStore {
	
	static final Logger logger = LoggerFactory.getLogger(MultiFormatStore.class);
	
	public enum Verbose {
		COMPACT,		// SPDX identifiers are used for any SPDX element references, license expressions as text
		STANDARD,		// Expand referenced SPDX element, license expressions as text
		FULL			// Expand all licenses to full objects and expand all SPDX elements
	};
	
	public enum Format {
		JSON,
		JSON_PRETTY,	// pretty printed JSON format
		XML,
		YAML
	}
	
	private Format format;
	private Verbose verbose;
	static final ObjectMapper JSON_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	static final ObjectMapper XML_MAPPER = new XmlMapper().configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true).enable(SerializationFeature.INDENT_OUTPUT);
	static final YAMLFactory yamlFactory = new YAMLFactory();
	static final ObjectMapper YAML_MAPPER = new ObjectMapper(yamlFactory);
	static final XmlFactory xmlFactory = new XmlFactory();
	
	private ObjectMapper mapper;
	
	/**
	 * @param format Format - XML, JSON or YAML
	 * @param verbose How verbose to make the document
	 */
	public MultiFormatStore(Format format, Verbose verbose) {
		super();
		Objects.requireNonNull(format);
		Objects.requireNonNull(verbose);
		this.format = format;
		this.verbose = verbose;
		setMapper();
	}
	
	/**
	 * Set the mapper based on the format
	 */
	private void setMapper() {
		switch (format) {
		case XML: mapper = XML_MAPPER; break;
		case YAML: mapper = YAML_MAPPER; break;
		case JSON: 
		case JSON_PRETTY: 
		default: mapper = JSON_MAPPER;
		}
	}
	
	/**
	 * Default compact version of MultiFormatStore
	 * @param format Format - XML, JSON or YAML
	 */
	public MultiFormatStore(Format format) {
		this(format, Verbose.COMPACT);
	}
	
	

	/**
	 * @return the format
	 */
	public synchronized Format getFormat() {
		return format;
	}



	/**
	 * @param format the format to set
	 */
	public synchronized void setFormat(Format format) {
		Objects.requireNonNull(format);
		this.format = format;
		setMapper();
	}



	/**
	 * @return the verbose
	 */
	public synchronized Verbose getVerbose() {
		return verbose;
	}



	/**
	 * @param verbose the verbose to set
	 */
	public synchronized void setVerbose(Verbose verbose) {
		Objects.requireNonNull(verbose);
		this.verbose = verbose;
		setMapper();
	}



	/* (non-Javadoc)
	 * @see org.spdx.storage.ISerializableModelStore#serialize(java.lang.String, java.io.OutputStream)
	 */
	@Override
	public synchronized void serialize(String documentUri, OutputStream stream) throws InvalidSPDXAnalysisException, IOException {
		JacksonSerializer serializer = new JacksonSerializer(mapper, format, verbose, this);
		ObjectNode output = serializer.docToJsonNode(documentUri);
		JsonGenerator jgen;
		switch (format) {
			case YAML: {
				jgen = yamlFactory.createGenerator(stream); 
				break;
			}
			case XML: {
				jgen = mapper.getFactory().createGenerator(stream).useDefaultPrettyPrinter(); 
				break;
			}
			case JSON: {
				jgen = mapper.getFactory().createGenerator(stream);
				break;
			}
			case JSON_PRETTY:
			default:  {
				jgen = mapper.getFactory().createGenerator(stream).useDefaultPrettyPrinter(); 
				break;
			}
		}
		try {
			mapper.writeTree(jgen, output);
		} finally {
			jgen.close();
		}
	}

	/**
	 * @param propertyName
	 * @return property name used for an array or collection of these values
	 */
	public static String propertyNameToCollectionPropertyName(String propertyName) {
		if (propertyName.endsWith("y")) {
			return propertyName.substring(0, propertyName.length()-1) + "ies";
		} else if (SpdxConstants.PROP_PACKAGE_LICENSE_INFO_FROM_FILES.equals(propertyName)) {
			return propertyName;
		} else {
			return propertyName + "s";
		}
	}
	
	public static String collectionPropertyNameToPropertyName(String collectionPropertyName) {
		if (collectionPropertyName.endsWith("ies")) {
			return collectionPropertyName.substring(0, collectionPropertyName.length()-3);
		} else if (SpdxConstants.PROP_PACKAGE_LICENSE_INFO_FROM_FILES.equals(collectionPropertyName)) {
			return collectionPropertyName;
		} else {
			return collectionPropertyName.substring(0, collectionPropertyName.length()-1);
		}
	}

	/* (non-Javadoc)
	 * @see org.spdx.storage.ISerializableModelStore#deSerialize(java.io.InputStream, boolean)
	 */
	@Override
	public synchronized String deSerialize(InputStream stream, boolean overwrite) throws InvalidSPDXAnalysisException, IOException {
		Objects.requireNonNull(stream, "Input stream must not be null");
		if (this.verbose != Verbose.COMPACT) {
			throw new InvalidSPDXAnalysisException("Only COMPACT verbose option is supported for deserialization");
		}
		JsonNode root = mapper.readTree(stream);
		JsonNode doc;
		if (Format.XML.equals(format)) {
			doc = root;
		} else {
			doc  = root.get("Document");
		}
		if (Objects.isNull(doc)) {
			throw new InvalidSPDXAnalysisException("Missing SPDX Document");
		}
		JsonNode namespaceNode = doc.get(SpdxConstants.PROP_DOCUMENT_NAMESPACE);
		if (Objects.isNull(namespaceNode)) {
			throw new InvalidSPDXAnalysisException("Missing document namespace");
		}
		String documentNamespace = namespaceNode.asText();
		if (Objects.isNull(documentNamespace) || documentNamespace.isEmpty()) {
			throw new InvalidSPDXAnalysisException("Empty document namespace");
		}
		IModelStoreLock lock = this.enterCriticalSection(documentNamespace, false);
		try {
			ConcurrentHashMap<String, StoredTypedItem> idMap = documentValues.get(documentNamespace);
			if (Objects.nonNull(idMap)) {
				if (!overwrite) {
					throw new InvalidSPDXAnalysisException("Document namespace "+documentNamespace+" already exists.");
				}
				idMap.clear();
			} else {
				while (idMap == null) {
					idMap = documentValues.putIfAbsent(documentNamespace, new ConcurrentHashMap<String, StoredTypedItem>());
				}
			}
		} finally {
			this.leaveCriticalSection(lock);
		}
		JacksonDeSerializer deSerializer = new JacksonDeSerializer(this);
		deSerializer.storeDocument(documentNamespace, doc);	
		return documentNamespace;

	}
}