package com.ilimi.taxonomy.mgr.impl;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.ekstep.common.slugs.Slug;
import org.ekstep.common.util.AWSUploader;
import org.ekstep.common.util.HttpDownloadUtility;
import org.ekstep.common.util.S3PropertyReader;
import org.ekstep.learning.common.enums.ContentAPIParams;
import org.ekstep.learning.common.enums.ContentErrorCodes;
import org.ekstep.learning.util.BaseLearningManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.ilimi.common.dto.NodeDTO;
import com.ilimi.common.dto.Request;
import com.ilimi.common.dto.Response;
import com.ilimi.common.exception.ServerException;
import com.ilimi.graph.dac.enums.GraphDACParams;
import com.ilimi.graph.dac.enums.RelationTypes;
import com.ilimi.graph.dac.model.Filter;
import com.ilimi.graph.dac.model.MetadataCriterion;
import com.ilimi.graph.dac.model.Node;
import com.ilimi.graph.dac.model.Relation;
import com.ilimi.graph.dac.model.SearchConditions;
import com.ilimi.graph.engine.router.GraphEngineManagers;
import com.ilimi.taxonomy.content.enums.ContentWorkflowPipelineParams;
import com.ilimi.taxonomy.dto.ContentSearchCriteria;
import com.ilimi.taxonomy.enums.ExtractionType;
import com.ilimi.taxonomy.mgr.IMimeTypeManager;
import com.ilimi.taxonomy.util.ContentBundle;
import com.ilimi.taxonomy.util.ContentPackageExtractionUtil;

public class BaseMimeTypeManager extends BaseLearningManager {

	@Autowired
	private ContentBundle contentBundle;

	private static final String tempFileLocation = "/data/contentBundle/";
	private static Logger LOGGER = LogManager.getLogger(IMimeTypeManager.class.getName());

	private static final String s3Content = "s3.content.folder";
	private static final String s3Artifact = "s3.artifact.folder";

	private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

	public boolean isArtifactUrlSet(Map<String, Object> contentMap) {
		return false;
	}

	public boolean isJSONValid(String content) {
		try {
			ObjectMapper mapper = new ObjectMapper();
			mapper.readTree(content);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	public boolean isECMLValid(String content) {
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder;
		try {
			dBuilder = dbFactory.newDocumentBuilder();
			dBuilder.parse(IOUtils.toInputStream(content, "UTF-8"));
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public Map<String, List<Object>> readECMLFile(String filePath) {
		final Map<String, List<Object>> mediaIdMap = new HashMap<String, List<Object>>();
		try {
			SAXParserFactory factory = SAXParserFactory.newInstance();
			SAXParser saxParser = factory.newSAXParser();
			DefaultHandler handler = new DefaultHandler() {
				public void startElement(String uri, String localName, String qName, Attributes attributes)
						throws SAXException {
					if (qName.equalsIgnoreCase("media")) {
						String id = attributes.getValue("id");
						if (StringUtils.isNotBlank(id)) {
							String src = attributes.getValue("src");
							if (StringUtils.isNotBlank(src)) {
								String assetId = attributes.getValue("assetId");
								List<Object> mediaValues = new ArrayList<Object>();
								mediaValues.add(src);
								mediaValues.add(assetId);
								mediaIdMap.put(id, mediaValues);
							}
						}
					}
				}

				public void endElement(String uri, String localName, String qName) throws SAXException {
					// System.out.println("End Element :" + qName);
				}
			};
			saxParser.parse(filePath, handler);
		} catch (Exception e) {
			throw new ServerException(ContentErrorCodes.ERR_CONTENT_EXTRACT.name(), e.getMessage());
		}
		return mediaIdMap;
	}

	public void delete(File file) throws IOException {
		if (file.isDirectory()) {
			// directory is empty, then delete it
			if (file.list().length == 0) {
				file.delete();
			} else {
				// list all the directory contents
				String files[] = file.list();
				for (String temp : files) {
					// construct the file structure
					File fileDelete = new File(file, temp);
					// recursive delete
					delete(fileDelete);
				}
				// check the directory again, if empty then delete it
				if (file.list().length == 0) {
					file.delete();
				}
			}

		} else {
			// if file, then delete it
			file.delete();
		}
	}

	protected Node setNodeStatus(Node node, String status) {
		if (null != node && !StringUtils.isBlank(status)) {
			Map<String, Object> metadata = node.getMetadata();
			if (null != metadata) {
				metadata.put(ContentAPIParams.status.name(), status);
				node.setMetadata(metadata);
			}
		}
		return node;
	}

	private void downloadAppIcon(Node node, String tempFolder) {
		String appIcon = (String) node.getMetadata().get("appIcon");
		if (StringUtils.isNotBlank(appIcon)) {
			File logoFile = HttpDownloadUtility.downloadFile(appIcon, tempFolder);
			try {
				if (null != logoFile && logoFile.exists() && logoFile.isFile()) {
					String parentFolderName = logoFile.getParent();
					File newName = new File(parentFolderName + File.separator + "logo.png");
					logoFile.renameTo(newName);
				}
			} catch (Exception ex) {
				throw new ServerException(ContentErrorCodes.ERR_CONTENT_PUBLISH.name(), ex.getMessage());
			}
		}
	}

	private void deleteTemp(String sourceFolder) {
		File directory = new File(sourceFolder);
		if (!directory.exists()) {
			System.out.println("Directory does not exist.");
		} else {
			try {
				delete(directory);
				if (!directory.exists()) {
					directory.mkdirs();
				}
			} catch (IOException e) {
				throw new ServerException(ContentErrorCodes.ERR_CONTENT_PUBLISH.name(), e.getMessage());
			}
		}

	}

	protected Response rePublish(Node node) {
		Response response = new Response();
		node = setNodeStatus(node, ContentAPIParams.Live.name());
		String tempFolder = tempFileLocation + File.separator + System.currentTimeMillis() + "_temp";
		File ecarFile = null;
		String artifactUrl = (String) node.getMetadata().get(ContentAPIParams.artifactUrl.name());
		if (StringUtils.isNotBlank(artifactUrl))
			ecarFile = HttpDownloadUtility.downloadFile(artifactUrl, tempFolder);
		try {
			if (null != ecarFile && ecarFile.exists() && ecarFile.isFile()) {
				File newName = new File(ecarFile.getParent() + File.separator + System.currentTimeMillis() + "_"
						+ node.getIdentifier() + "." + FilenameUtils.getExtension(ecarFile.getPath()));
				ecarFile.renameTo(newName);
				node.getMetadata().put(ContentAPIParams.downloadUrl.name(), newName);
			}
			downloadAppIcon(node, tempFolder);
			response = addDataToContentNode(node);
		} catch (Exception e) {
			throw new ServerException(ContentErrorCodes.ERR_CONTENT_PUBLISH.name(), e.getMessage());
		} finally {
			deleteTemp(tempFolder);
		}
		return response;
	}

	private Response addDataToContentNode(Node node) {
		Number pkgVersion = (Number) node.getMetadata().get("pkgVersion");
		if (null == pkgVersion || pkgVersion.intValue() < 1) {
			pkgVersion = 1.0;
		} else {
			pkgVersion = pkgVersion.doubleValue() + 1;
		}
		node.getMetadata().put("pkgVersion", pkgVersion);
		List<Node> nodes = new ArrayList<Node>();
		nodes.add(node);
		List<Map<String, Object>> ctnts = new ArrayList<Map<String, Object>>();
		List<String> childrenIds = new ArrayList<String>();
		getContentBundleData(node.getGraphId(), nodes, ctnts, childrenIds);
		String bundleFileName = Slug
				.makeSlug((String) node.getMetadata().get(ContentWorkflowPipelineParams.name.name()), true) + "_"
				+ System.currentTimeMillis() + "_" + node.getIdentifier() + "_"
				+ node.getMetadata().get(ContentWorkflowPipelineParams.pkgVersion.name()) + ".ecar";
		Map<Object, List<String>> downloadUrls = contentBundle.createContentManifestData(ctnts, childrenIds, null);
		String[] urlArray = contentBundle.createContentBundle(ctnts, bundleFileName, "1.1", downloadUrls,
				node.getIdentifier());
		node.getMetadata().put(ContentAPIParams.s3Key.name(), urlArray[0]);
		node.getMetadata().put("downloadUrl", urlArray[1]);
		node.getMetadata().put("status", "Live");
		node.getMetadata().put(ContentAPIParams.lastPublishedOn.name(), formatCurrentDate());
		node.getMetadata().put(ContentAPIParams.size.name(), getS3FileSize(urlArray[0]));
		Node newNode = new Node(node.getIdentifier(), node.getNodeType(), node.getObjectType());
		newNode.setGraphId(node.getGraphId());
		newNode.setMetadata(node.getMetadata());
		return updateContentNode(newNode, urlArray[1]);
	}

	protected Double getS3FileSize(String key) {
		Double bytes = null;
		if (StringUtils.isNotBlank(key)) {
			try {
				return AWSUploader.getObjectSize(key);
			} catch (IOException e) {
				LOGGER.error("Error: While getting the file size from AWS", e);
			}
		}
		return bytes;
	}

	private static String formatCurrentDate() {
		return format(new Date());
	}

	private static String format(Date date) {
		if (null != date) {
			try {
				return sdf.format(date);
			} catch (Exception e) {
			}
		}
		return null;
	}

	protected Response updateContentNode(Node node, String url) {
		Response updateRes = updateNode(node);
		if (StringUtils.isNotBlank(url))
			updateRes.put(ContentAPIParams.content_url.name(), url);
		return updateRes;
	}

	protected Response updateNode(Node node) {
		Request updateReq = getRequest(node.getGraphId(), GraphEngineManagers.NODE_MANAGER, "updateDataNode");
		updateReq.put(GraphDACParams.node.name(), node);
		updateReq.put(GraphDACParams.node_id.name(), node.getIdentifier());
		Response updateRes = getResponse(updateReq, LOGGER);
		return updateRes;
	}

	protected void getContentBundleData(String taxonomyId, List<Node> nodes, List<Map<String, Object>> ctnts,
			List<String> childrenIds) {
		Map<String, Node> nodeMap = new HashMap<String, Node>();
		if (null != nodes && !nodes.isEmpty()) {
			for (Node node : nodes) {
				getContentRecursive(taxonomyId, node, nodeMap, childrenIds, ctnts);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void getContentRecursive(String taxonomyId, Node node, Map<String, Node> nodeMap, List<String> childrenIds,
			List<Map<String, Object>> ctnts) {
		if (!nodeMap.containsKey(node.getIdentifier())) {
			nodeMap.put(node.getIdentifier(), node);
			Map<String, Object> metadata = new HashMap<String, Object>();
			if (null == node.getMetadata())
				node.setMetadata(new HashMap<String, Object>());
			String status = (String) node.getMetadata().get("status");
			if (StringUtils.equalsIgnoreCase("Live", status)) {
				metadata.putAll(node.getMetadata());
				metadata.put("identifier", node.getIdentifier());
				metadata.put("objectType", node.getObjectType());
				metadata.put("subject", node.getGraphId());
				metadata.remove("body");
				metadata.remove("editorState");
				if (null != node.getTags() && !node.getTags().isEmpty())
					metadata.put("tags", node.getTags());
				List<String> searchIds = new ArrayList<String>();
				if (null != node.getOutRelations() && !node.getOutRelations().isEmpty()) {
					List<NodeDTO> children = new ArrayList<NodeDTO>();
					List<NodeDTO> preRequisites = new ArrayList<NodeDTO>();
					for (Relation rel : node.getOutRelations()) {
						if (StringUtils.equalsIgnoreCase(RelationTypes.SEQUENCE_MEMBERSHIP.relationName(),
								rel.getRelationType())
								&& StringUtils.equalsIgnoreCase(node.getObjectType(), rel.getEndNodeObjectType())) {
							childrenIds.add(rel.getEndNodeId());
							if (!nodeMap.containsKey(rel.getEndNodeId())) {
								searchIds.add(rel.getEndNodeId());
							}
							children.add(new NodeDTO(rel.getEndNodeId(), rel.getEndNodeName(),
									rel.getEndNodeObjectType(), rel.getRelationType(), rel.getMetadata()));
						} else if (StringUtils.equalsIgnoreCase(RelationTypes.PRE_REQUISITE.relationName(),
								rel.getRelationType())
								&& StringUtils.equalsIgnoreCase(ContentWorkflowPipelineParams.Library.name(),
										rel.getEndNodeObjectType())) {
							childrenIds.add(rel.getEndNodeId());
							if (!nodeMap.containsKey(rel.getEndNodeId())) {
								searchIds.add(rel.getEndNodeId());
							}
							preRequisites.add(new NodeDTO(rel.getEndNodeId(), rel.getEndNodeName(),
									rel.getEndNodeObjectType(), rel.getRelationType(), rel.getMetadata()));
						}
					}
					if (!children.isEmpty()) {
						metadata.put("children", children);
					}
					if (!preRequisites.isEmpty()) {
						metadata.put(ContentWorkflowPipelineParams.pre_requisites.name(), preRequisites);
					}
				}
				ctnts.add(metadata);
				if (!searchIds.isEmpty()) {
					Response searchRes = searchNodes(taxonomyId, searchIds);
					if (checkError(searchRes)) {
						throw new ServerException(ContentErrorCodes.ERR_CONTENT_SEARCH_ERROR.name(),
								getErrorMessage(searchRes));
					} else {
						List<Object> list = (List<Object>) searchRes.get(ContentAPIParams.contents.name());
						if (null != list && !list.isEmpty()) {
							for (Object obj : list) {
								List<Node> nodeList = (List<Node>) obj;
								for (Node child : nodeList) {
									getContentRecursive(taxonomyId, child, nodeMap, childrenIds, ctnts);
								}
							}
						}
					}
				}
			}
		}
	}

	@SuppressWarnings("unused")
	private double getFileSizeInKB(File file) {
		double bytes = 0;
		try {
			bytes = getFileSize(file) / 1024;
		} catch (IOException e) {
			LOGGER.error("Error: While Calculating the file size.", e);
		}
		return bytes;
	}

	private double getFileSize(File file) throws IOException {
		double bytes = 0;
		if (file.exists()) {
			bytes = file.length();
		}
		return bytes;
	}

	private Response searchNodes(String taxonomyId, List<String> contentIds) {
		ContentSearchCriteria criteria = new ContentSearchCriteria();
		List<Filter> filters = new ArrayList<Filter>();
		Filter filter = new Filter("identifier", SearchConditions.OP_IN, contentIds);
		filters.add(filter);
		MetadataCriterion metadata = MetadataCriterion.create(filters);
		metadata.addFilter(filter);
		criteria.setMetadata(metadata);
		List<Request> requests = new ArrayList<Request>();
		if (StringUtils.isNotBlank(taxonomyId)) {
			Request req = getRequest(taxonomyId, GraphEngineManagers.SEARCH_MANAGER, "searchNodes",
					GraphDACParams.search_criteria.name(), criteria.getSearchCriteria());
			req.put(GraphDACParams.get_tags.name(), true);
			requests.add(req);
		} else {
			for (String tId : TaxonomyManagerImpl.taxonomyIds) {
				Request req = getRequest(tId, GraphEngineManagers.SEARCH_MANAGER, "searchNodes",
						GraphDACParams.search_criteria.name(), criteria.getSearchCriteria());
				req.put(GraphDACParams.get_tags.name(), true);
				requests.add(req);
			}
		}
		Response response = getResponse(requests, LOGGER, GraphDACParams.node_list.name(),
				ContentAPIParams.contents.name());
		return response;
	}

	public String[] uploadArtifactToAWS(File uploadedFile, String identifier) {
		String[] urlArray = new String[] {};
		try {
			String folder = S3PropertyReader.getProperty(s3Content);
			folder = folder + "/" + Slug.makeSlug(identifier, true) + "/" + S3PropertyReader.getProperty(s3Artifact);
			urlArray = AWSUploader.uploadFile(folder, uploadedFile);
		} catch (Exception e) {
			throw new ServerException(ContentErrorCodes.ERR_CONTENT_UPLOAD_FILE.name(),
					"Error wihile uploading the File.", e);
		}
		return urlArray;
	}

	public Response uploadContentArtifact(Node node, File uploadedFile) {
		String[] urlArray = uploadArtifactToAWS(uploadedFile, node.getIdentifier());
		node.getMetadata().put("s3Key", urlArray[0]);
		node.getMetadata().put(ContentAPIParams.artifactUrl.name(), urlArray[1]);
		
		ContentPackageExtractionUtil contentPackageExtractionUtil = new ContentPackageExtractionUtil();
		contentPackageExtractionUtil.extractContentPackage(node, uploadedFile, ExtractionType.snapshot);
		
		return updateContentNode(node, urlArray[1]);
	}

	public String getKeyName(String url) {
		return url.substring(url.lastIndexOf('/') + 1);
	}

	public Number getNumericValue(Object obj) {
		try {
			return (Number) obj;
		} catch (Exception e) {
			return 0;
		}
	}

	public Double getDoubleValue(Object obj) {
		Number n = getNumericValue(obj);
		if (null == n)
			return 0.0;
		return n.doubleValue();
	}

	protected String getBasePath(String contentId) {
		String path = "";
		if (!StringUtils.isBlank(contentId))
			path = tempFileLocation + File.separator + System.currentTimeMillis() + ContentAPIParams._temp.name()
					+ File.separator + contentId;
		return path;
	}

}
