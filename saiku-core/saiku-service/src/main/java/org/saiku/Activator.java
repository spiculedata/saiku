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

package org.saiku;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Created by bugg on 04/02/15.
 */
public class Activator implements BundleActivator {
    public void start(BundleContext bundleContext) throws Exception {
        System.out.println("Hello World");
    }

    public void stop(BundleContext bundleContext) throws Exception {
        System.out.println("Goodbye World");
    }

    public void onBindService(final Object sampleService) {
        if (sampleService == null) {
            System.out.println("sample service is null");
        } else {
            // System.out.println("greet: " + sampleService.getGreeting("bob"));
        }
    }

    public void onUnbindService(final Object sampleService) {
        System.out.println("service unbound");
    }
}
