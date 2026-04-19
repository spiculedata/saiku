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

package mondrian.olap4j;

import java.util.Map;
import mondrian.olap.Annotation;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;

/**
 * Created by bugg on 20/11/14.
 */
public interface LevelInterface {

    String getName();

    String getUniqueName();

    String getCaption();

    String getDescription();

    Dimension getDimension();

    Hierarchy getHierarchy();

    boolean isVisible();

    Level.Type getLevelType();

    Map<String, Annotation> getAnnotations();
}
