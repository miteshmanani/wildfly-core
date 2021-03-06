/*
* JBoss, Home of Professional Open Source.
* Copyright 2011, Red Hat Middleware LLC, and individual contributors
* as indicated by the @author tags. See the copyright.txt file in the
* distribution for a full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*/
package org.jboss.as.subsystem.test;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;

import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.controller.Extension;
import org.jboss.as.controller.ManagementModel;
import org.jboss.as.controller.ModelControllerServiceInitialization;
import org.jboss.as.controller.RunningModeControl;
import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.operations.global.GlobalNotifications;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.model.test.ModelTestModelControllerService;
import org.jboss.as.model.test.ModelTestOperationValidatorFilter;
import org.jboss.as.model.test.StringConfigurationPersister;
import org.jboss.as.repository.ContentRepository;
import org.jboss.as.server.DeployerChainAddHandler;
import org.jboss.as.server.ServerEnvironment;
import org.jboss.as.server.ServerEnvironment.LaunchType;
import org.jboss.as.server.controller.resources.ServerDeploymentResourceDefinition;
import org.jboss.as.subsystem.test.ControllerInitializer.TestControllerAccessor;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.vfs.VirtualFile;

/**
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 */
class TestModelControllerService extends ModelTestModelControllerService implements TestControllerAccessor {
    private final Extension mainExtension;
    private final AdditionalInitialization additionalInit;
    private final ControllerInitializer controllerInitializer;
    private final ExtensionRegistry extensionRegistry;
    private final RunningModeControl runningModeControl;
    private final ContentRepository contentRepository = new MockContentRepository();
    private final boolean registerTransformers;

    protected TestModelControllerService(final Extension mainExtension, final ControllerInitializer controllerInitializer,
                                         final AdditionalInitialization additionalInit, final RunningModeControl runningModeControl, final ExtensionRegistry extensionRegistry,
                                         final StringConfigurationPersister persister, final ModelTestOperationValidatorFilter validateOpsFilter, final boolean registerTransformers) {
        super(additionalInit.getProcessType(), runningModeControl, extensionRegistry.getTransformerRegistry(), persister, validateOpsFilter,
                ModelTestModelControllerService.DESC_PROVIDER, new ControlledProcessState(true), Controller80x.INSTANCE);
        this.mainExtension = mainExtension;
        this.additionalInit = additionalInit;
        this.controllerInitializer = controllerInitializer;
        this.extensionRegistry = extensionRegistry;
        this.runningModeControl = runningModeControl;
        this.registerTransformers = registerTransformers;
    }

    static TestModelControllerService create(final Extension mainExtension, final ControllerInitializer controllerInitializer,
                                             final AdditionalInitialization additionalInit, final ExtensionRegistry extensionRegistry,
                                             final StringConfigurationPersister persister, final ModelTestOperationValidatorFilter validateOpsFilter, final boolean registerTransformers) {
        return new TestModelControllerService(mainExtension, controllerInitializer, additionalInit, new RunningModeControl(additionalInit.getRunningMode()), extensionRegistry, persister, validateOpsFilter, registerTransformers);
    }

    @Override
    protected void performControllerInitialization(ServiceTarget target, ManagementModel managementModel) {
        super.performControllerInitialization(target, managementModel);
        if (additionalInit.getProcessType().isServer()) {
            final ServiceLoader<ModelControllerServiceInitialization> sl = ServiceLoader.load(ModelControllerServiceInitialization.class);
            for (ModelControllerServiceInitialization init : sl) {
                    init.initializeStandalone(target, managementModel);
            }
        }
    }

    @Override
    protected void initExtraModel(ManagementModel managementModel) {
        Resource rootResource = managementModel.getRootResource();
        ManagementResourceRegistration rootRegistration = managementModel.getRootResourceRegistration();
        initExtraModelInternal(rootResource, rootRegistration);
        additionalInit.initializeExtraSubystemsAndModel(extensionRegistry, rootResource, rootRegistration,
                managementModel.getCapabilityRegistry());
    }

    /** @deprecated only for legacy version support */
    @Override
    protected void initExtraModel(Resource rootResource, ManagementResourceRegistration rootRegistration) {
        initExtraModelInternal(rootResource, rootRegistration);
        additionalInit.initializeExtraSubystemsAndModel(extensionRegistry, rootResource, rootRegistration);
    }

    private void initExtraModelInternal(Resource rootResource, ManagementResourceRegistration rootRegistration) {
        rootResource.getModel().get(SUBSYSTEM);

        rootRegistration.registerSubModel(ServerDeploymentResourceDefinition.create(contentRepository, null));

        GlobalNotifications.registerGlobalNotifications(rootRegistration, processType);
        controllerInitializer.setTestModelControllerAccessor(this);
        controllerInitializer.initializeModel(rootResource, rootRegistration);
    }

    @Override
    protected void preBoot(List<ModelNode> bootOperations, boolean rollbackOnRuntimeFailure) {
        mainExtension.initialize(extensionRegistry.getExtensionContext("Test", getRootRegistration(), registerTransformers));
    }

    protected void postBoot() {
        DeployerChainAddHandler.INSTANCE.clearDeployerMap();
    }

    private void delete(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                delete(child);
            }
        }
        file.delete();
    }

    public ServerEnvironment getServerEnvironment() {
        Properties props = new Properties();
        File home = new File("target/jbossas");
        delete(home);
        home.mkdir();
        props.put(ServerEnvironment.HOME_DIR, home.getAbsolutePath());

        File standalone = new File(home, "standalone");
        standalone.mkdir();
        props.put(ServerEnvironment.SERVER_BASE_DIR, standalone.getAbsolutePath());

        File configuration = new File(standalone, "configuration");
        configuration.mkdir();
        props.put(ServerEnvironment.SERVER_CONFIG_DIR, configuration.getAbsolutePath());

        File xml = new File(configuration, "standalone.xml");
        try {
            xml.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        props.put(ServerEnvironment.JBOSS_SERVER_DEFAULT_CONFIG, "standalone.xml");

        return new ServerEnvironment(null, props, new HashMap<String, String>(), "standalone.xml", null, LaunchType.STANDALONE, runningModeControl.getRunningMode(), null);
    }

    private static class MockContentRepository implements ContentRepository {

        @Override
        public byte[] addContent(InputStream stream) throws IOException {
            return null;
        }

        @Override
        public VirtualFile getContent(byte[] hash) {
            return null;
        }

        @Override
        public boolean hasContent(byte[] hash) {
            return false;
        }

        @Override
        public boolean syncContent(byte[] hash) {
            return false;
        }

        @Override
        public void removeContent(byte[] hash, Object reference) {
        }

        @Override
        public void addContentReference(byte[] hash, Object reference) {
        }
    }
}
