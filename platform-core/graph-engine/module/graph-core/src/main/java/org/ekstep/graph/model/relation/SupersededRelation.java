package org.ekstep.graph.model.relation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.ekstep.common.dto.Request;
import org.ekstep.common.exception.ServerException;
import org.ekstep.graph.common.mgr.BaseGraphManager;
import org.ekstep.graph.dac.enums.RelationTypes;
import org.ekstep.graph.dac.model.Node;
import org.ekstep.graph.exception.GraphRelationErrorCodes;

public class SupersededRelation extends AbstractRelation {

	protected SupersededRelation(BaseGraphManager manager, String graphId, String startNodeId, String endNodeId) {
		super(manager, graphId, startNodeId, endNodeId);
	}

	@Override
	public String getRelationType() {
		return RelationTypes.SUPERSEDED.relationName();
	}

	@Override
	public Map<String, List<String>> validateRelation(Request request) {
		try {
			List<String> futures = new ArrayList<String>();
			Node startNode = getNode(request, this.startNodeId);
			Node endNode = getNode(request, this.endNodeId);
			String startNodeType = getNodeTypeFuture(startNode);
			String endNodeType = getNodeTypeFuture(endNode);
			String nodeTypeMessages = compareFutures(startNodeType, endNodeType, "nodeType");
			futures.add(nodeTypeMessages);

			// check if the relation is valid between object type definitions.
			String startNodeObjectType = getObjectTypeFuture(startNode);
			String endNodeObjectType = getObjectTypeFuture(endNode);
			String objectTypeMessages = compareFutures(startNodeObjectType, endNodeObjectType, "objectType");
			futures.add(objectTypeMessages);

			return getMessageMap(futures);
		} catch (Exception e) {
			throw new ServerException(GraphRelationErrorCodes.ERR_RELATION_VALIDATE.name(), e.getMessage(), e);
		}
	}

}
