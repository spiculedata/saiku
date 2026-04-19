/*
 * Copyright 2014 OSBI Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.saiku.repository;

import java.io.IOException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import org.apache.commons.io.FilenameUtils;
import org.apache.jackrabbit.server.io.DefaultHandler;
import org.apache.jackrabbit.server.io.IOManager;
import org.apache.jackrabbit.server.io.ImportContext;

/**
 * Created by bugg on 10/12/14.
 */
public class SaikuDavResourceImpl extends DefaultHandler {

    public SaikuDavResourceImpl() {}

    public SaikuDavResourceImpl(IOManager ioManager) {
        super(ioManager);
    }

    public SaikuDavResourceImpl(
            IOManager ioManager, String collectionNodetype, String defaultNodetype, String contentNodetype) {
        super(ioManager, collectionNodetype, defaultNodetype, contentNodetype);
    }

    @Override
    public boolean importContent(ImportContext context, boolean isCollection) throws IOException {
        if (!canImport(context, isCollection)) {
            throw new IOException(getName() + ": Cannot import " + context.getSystemId());
        }

        boolean success = false;
        try {

            Node contentNode = getContentNode(context, isCollection);
            String ext = FilenameUtils.getExtension(context.getSystemId());
            if (ext.equals("saiku")) {
                contentNode.getParent().addMixin("nt:saikufiles");
            } else if (ext.equals("xml")) {
                contentNode.getParent().addMixin("nt:mondrianschema");
            } else if (ext.equals("sds")) {
                contentNode.getParent().addMixin("nt:olapdatasource");
            } else if (isCollection) {
                contentNode.getParent().addMixin("nt:saikufolders");
            }

            // contentNode.addNode("jcr:content", "nt:resource");

            success = importData(context, isCollection, contentNode);
            if (success) {
                success = importProperties(context, isCollection, contentNode);
            }
        } catch (RepositoryException e) {
            success = false;
            throw new IOException(e.getMessage());
        } finally {
            // revert any changes made in case the import failed.
            if (!success) {
                try {
                    context.getImportRoot().refresh(false);
                } catch (RepositoryException e) {
                    throw new IOException(e.getMessage());
                }
            }
        }
        return success;
    }
}
