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

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * SOLR-side representation of node metadata information.
 * 
 * @since 4.0
 */
@Data
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
}
