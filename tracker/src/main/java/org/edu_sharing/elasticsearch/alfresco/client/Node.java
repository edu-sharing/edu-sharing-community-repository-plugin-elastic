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

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
public class Node
{
    private long id;
    private String nodeRef;
    private long txnId;
    private String status;
    private String tenant;
    private long aclId;
    /**
     * -- GETTER --
     *  The property value to use for sharding - as requested
     * <p>
     * null - if the node does not have the property, the standard "String" value of the property if it is present on the node.
     * For dates and datetime properties this will be the ISO formatted datetime.
     */
    private String shardPropertyValue;


    @Override
    public String toString()
    {
        return "Node [id=" + this.id + ", nodeRef=" + this.nodeRef + ", txnId=" + this.txnId
                    + ", status=" + this.status + ", tenant=" + this.tenant + ", aclId="
                    + this.aclId + ", shardPropertyValue=" + this.shardPropertyValue + "]";
    }

    @Override
    public boolean equals(Object obj) {
        if(obj == null) return false;
        if(!(obj instanceof Node)) return false;
        return this.getId() == ((Node)obj).getId();
    }
}
