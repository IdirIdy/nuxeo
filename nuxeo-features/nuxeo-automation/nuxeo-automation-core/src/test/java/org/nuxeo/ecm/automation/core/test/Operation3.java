/*
 * (C) Copyright 2006-2008 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     bstefanescu
 */
package org.nuxeo.ecm.automation.core.test;

import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;

/**
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 *
 */
@Operation(id="o3")
public class Operation3 {

    @Param(name="message") protected String message;

    @Context OperationContext ctx;
    @Context CoreSession session;

    @OperationMethod
    public DocumentModel printInfo1(DocumentModel doc) throws Exception {
        //System.out.println("O2:doc:doc: "+doc.getId()+". Session: "+session+". message: "+message);
        Helper.updateContext(ctx, "O3:doc:doc", message, doc.getTitle());
        return doc;
    }

    @OperationMethod
    public DocumentRef printInfo2(DocumentModel doc) throws Exception {
        //System.out.println("O2:doc:ref: "+doc.getId()+". Session: "+session+". message: "+message);
        Helper.updateContext(ctx, "O3:doc:ref", message, doc.getTitle());
        return doc.getRef();
    }


}