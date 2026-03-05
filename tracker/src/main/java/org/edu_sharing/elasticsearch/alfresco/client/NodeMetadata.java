/*
 * #%L
 * Alfresco Solr Client
 * %%
 * Copyright (C) 2005 - 2016 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software. 
 * If the software was purchased under a paid Alfresco license, the terms of 
 * the paid license agreement will prevail.  Otherwise, the software is 
 * provided under the following open source license terms:
 * 
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.edu_sharing.elasticsearch.alfresco.client;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * SOLR-side representation of node metadata information.
 * 
 * @since 4.0
 */
public class NodeMetadata
{
    private long id;
    private String nodeRef;
    private String type;
    private long aclId;
    private Map<String, Serializable> properties;
    private Set<String> aspects;
    private List<Path> paths;
    private List<NamePath> namePaths;
    private long parentAssocsCrc;
    private List<String> parentAssocs;

    private List<String> childAssocs;
    private List<Long> childIds;
    private String owner;
    private long txnId;
    private Set<String> ancestors;
    private String tenantDomain;
	private List<String> ancestorPaths;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getNodeRef() {
        return nodeRef;
    }

    public void setNodeRef(String nodeRef) {
        this.nodeRef = nodeRef;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getAclId() {
        return aclId;
    }

    public void setAclId(long aclId) {
        this.aclId = aclId;
    }

    public Map<String, Serializable> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Serializable> properties) {
        this.properties = properties;
    }

    public Set<String> getAspects() {
        return aspects;
    }

    public void setAspects(Set<String> aspects) {
        this.aspects = aspects;
    }

    public List<Path> getPaths() {
        return paths;
    }

    public void setPaths(List<Path> paths) {
        this.paths = paths;
    }

    public List<NamePath> getNamePaths() {
        return namePaths;
    }

    public void setNamePaths(List<NamePath> namePaths) {
        this.namePaths = namePaths;
    }

    public long getParentAssocsCrc() {
        return parentAssocsCrc;
    }

    public void setParentAssocsCrc(long parentAssocsCrc) {
        this.parentAssocsCrc = parentAssocsCrc;
    }

    public List<String> getParentAssocs() {
        return parentAssocs;
    }

    public void setParentAssocs(List<String> parentAssocs) {
        this.parentAssocs = parentAssocs;
    }

    public List<String> getChildAssocs() {
        return childAssocs;
    }

    public void setChildAssocs(List<String> childAssocs) {
        this.childAssocs = childAssocs;
    }

    public List<Long> getChildIds() {
        return childIds;
    }

    public void setChildIds(List<Long> childIds) {
        this.childIds = childIds;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public long getTxnId() {
        return txnId;
    }

    public void setTxnId(long txnId) {
        this.txnId = txnId;
    }

    public Set<String> getAncestors() {
        return ancestors;
    }

    public void setAncestors(Set<String> ancestors) {
        this.ancestors = ancestors;
    }

    public String getTenantDomain() {
        return tenantDomain;
    }

    public void setTenantDomain(String tenantDomain) {
        this.tenantDomain = tenantDomain;
    }

    public List<String> getAncestorPaths() {
        return ancestorPaths;
    }

    public void setAncestorPaths(List<String> ancestorPaths) {
        this.ancestorPaths = ancestorPaths;
    }

    @Override
    public String toString()
    {
        return "NodeMetadata [id="
                + id + ", nodeRef=" + nodeRef + ", type=" + type + ", aclId=" + aclId + ", properties=" + properties + ", aspects=" + aspects + ", paths=" + paths
                + ", parentAssocsCrc=" + parentAssocsCrc + ", parentAssocs=" + parentAssocs + ", childAssocs=" + childAssocs + ", childIds=" + childIds + ", owner=" + owner
                + ", txnId=" + txnId + ", ancestors=" + ancestors + ", tenantDomain=" + tenantDomain + "]";
    }
}
