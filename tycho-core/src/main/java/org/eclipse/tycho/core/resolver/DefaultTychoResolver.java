/*******************************************************************************
 * Copyright (c) 2008, 2019 Sonatype Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sonatype Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.core.resolver;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.artifacts.DependencyArtifacts;
import org.eclipse.tycho.artifacts.TargetPlatform;
import org.eclipse.tycho.core.ArtifactDependencyVisitor;
import org.eclipse.tycho.core.DependencyResolver;
import org.eclipse.tycho.core.DependencyResolverConfiguration;
import org.eclipse.tycho.core.TargetPlatformConfiguration;
import org.eclipse.tycho.core.TychoConstants;
import org.eclipse.tycho.core.TychoProject;
import org.eclipse.tycho.core.ee.ExecutionEnvironmentConfigurationImpl;
import org.eclipse.tycho.core.ee.shared.ExecutionEnvironmentConfiguration;
import org.eclipse.tycho.core.osgitools.AbstractTychoProject;
import org.eclipse.tycho.core.osgitools.DebugUtils;
import org.eclipse.tycho.core.osgitools.DefaultReactorProject;
import org.eclipse.tycho.core.resolver.shared.PlatformPropertiesUtils;
import org.eclipse.tycho.core.utils.TychoProjectUtils;
import org.eclipse.tycho.resolver.DependencyVisitor;
import org.eclipse.tycho.resolver.TychoResolver;

@Component(role = TychoResolver.class)
public class DefaultTychoResolver implements TychoResolver {

    @Requirement
    private Logger logger;

    @Requirement
    private DefaultTargetPlatformConfigurationReader configurationReader;

    @Requirement
    private DefaultDependencyResolverFactory dependencyResolverLocator;

    @Requirement(role = TychoProject.class)
    private Map<String, TychoProject> projectTypes;

    public static final String TYCHO_ENV_OSGI_WS = "tycho.env.osgi.ws";
    public static final String TYCHO_ENV_OSGI_OS = "tycho.env.osgi.os";
    public static final String TYCHO_ENV_OSGI_ARCH = "tycho.env.osgi.arch";

    @Override
    public void setupProject(MavenSession session, MavenProject project, ReactorProject reactorProject) {
        AbstractTychoProject dr = (AbstractTychoProject) projectTypes.get(project.getPackaging());
        if (dr == null) {
            return;
        }

        // skip if setup was already done
        if (project.getContextValue(TychoConstants.CTX_MERGED_PROPERTIES) != null) {
            return;
        }

        // generic Eclipse/OSGi metadata

        dr.setupProject(session, project);

        // p2 metadata

        Properties properties = new Properties();
        properties.putAll(project.getProperties());
        properties.putAll(session.getSystemProperties()); // session wins
        properties.putAll(session.getUserProperties());
        project.setContextValue(TychoConstants.CTX_MERGED_PROPERTIES, properties);

        setTychoEnvironmentProperties(properties, project);

        TargetPlatformConfiguration configuration = configurationReader.getTargetPlatformConfiguration(session,
                project);
        project.setContextValue(TychoConstants.CTX_TARGET_PLATFORM_CONFIGURATION, configuration);

        ExecutionEnvironmentConfiguration eeConfiguration = new ExecutionEnvironmentConfigurationImpl(logger,
                !configuration.isResolveWithEEConstraints());
        dr.readExecutionEnvironmentConfiguration(project, eeConfiguration);
        project.setContextValue(TychoConstants.CTX_EXECUTION_ENVIRONMENT_CONFIGURATION, eeConfiguration);

        DependencyResolver resolver = dependencyResolverLocator.lookupDependencyResolver(project);
        resolver.setupProjects(session, project, reactorProject);
    }

    @Override
    public void resolveProject(MavenSession session, MavenProject project, List<ReactorProject> reactorProjects) {
        AbstractTychoProject dr = (AbstractTychoProject) projectTypes.get(project.getPackaging());
        if (dr == null) {
            return;
        }

        DependencyResolver resolver = dependencyResolverLocator.lookupDependencyResolver(project);

        logger.info("Computing target platform for " + project);
        TargetPlatform preliminaryTargetPlatform = resolver.computePreliminaryTargetPlatform(session, project,
                reactorProjects);

        TargetPlatformConfiguration configuration = TychoProjectUtils.getTargetPlatformConfiguration(project);

        DependencyResolverConfiguration resolverConfiguration = configuration.getDependencyResolverConfiguration();

        logger.info("Resolving dependencies of " + project);
        // store EE configuration in case second resolution has to be applied due to Java version removing modules
        ExecutionEnvironmentConfiguration oldEE = TychoProjectUtils.getExecutionEnvironmentConfiguration(project);
        String oldEEVersion = oldEE.getProfileName().substring(7);
        String javaVersion = System.getProperty("java.specification.version");
        String newEEName = "JavaSE-" + javaVersion;
        DependencyArtifacts dependencyArtifacts;
        // Due to Java 11 being the first Java version to remove there is the need for special resolution as 
        // it's no longer guaranteed that a Java version contains all packages from the previous. In this case 
        // resolve with current running Java version. if 
        // There is the issue that it was possible to resolve newer Java versions than the running ones relying on the EE
        // profile file. In order to keep this possibility (although it will only work if no new APIs are actually used)
        // if the EE profile specified is newer than the running version it fallbacks to the old profile file way.
        if (javaVersion.compareTo("11") >= 0 && oldEE.getProfileName().startsWith("JavaSE")
                && javaVersion.compareTo(oldEEVersion) > 0 && !oldEE.getProfileName().equals(newEEName)) {
            dependencyArtifacts = resolveWithCurrentEE(session, project, newEEName);
        } else {
            dependencyArtifacts = resolver.resolveDependencies(session, project, preliminaryTargetPlatform,
                    reactorProjects, resolverConfiguration);
        }

        if (logger.isDebugEnabled() && DebugUtils.isDebugEnabled(session, project)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Resolved target platform for ").append(project).append("\n");
            dependencyArtifacts.toDebugString(sb, "  ");
            logger.debug(sb.toString());
        }

        dr.setDependencyArtifacts(session, project, dependencyArtifacts);

        logger.info("Resolving class path of " + project);
        dr.resolveClassPath(session, project);

        //reset EE to original one
        project.setContextValue(TychoConstants.CTX_EXECUTION_ENVIRONMENT_CONFIGURATION, oldEE);

        resolver.injectDependenciesIntoMavenModel(project, dr, dependencyArtifacts, logger);

        if (logger.isDebugEnabled() && DebugUtils.isDebugEnabled(session, project)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Injected dependencies for ").append(project.toString()).append("\n");
            for (Dependency dependency : project.getDependencies()) {
                sb.append("  ").append(dependency.toString());
            }
            logger.debug(sb.toString());
        }
    }

    @Override
    public void traverse(MavenProject project, final DependencyVisitor visitor) {
        TychoProject tychoProject = projectTypes.get(project.getPackaging());
        if (tychoProject != null) {
            tychoProject.getDependencyWalker(project).walk(new ArtifactDependencyVisitor() {
                @Override
                public void visitPlugin(org.eclipse.tycho.core.PluginDescription plugin) {
                    visitor.visit(plugin);
                }

                @Override
                public boolean visitFeature(org.eclipse.tycho.core.FeatureDescription feature) {
                    return visitor.visit(feature);
                }
            });
        } else {
            // TODO do something!
        }
    }

    protected void setTychoEnvironmentProperties(Properties properties, MavenProject project) {
        String arch = PlatformPropertiesUtils.getArch(properties);
        String os = PlatformPropertiesUtils.getOS(properties);
        String ws = PlatformPropertiesUtils.getWS(properties);
        project.getProperties().put(TYCHO_ENV_OSGI_WS, ws);
        project.getProperties().put(TYCHO_ENV_OSGI_OS, os);
        project.getProperties().put(TYCHO_ENV_OSGI_ARCH, arch);
    }

    private DependencyArtifacts resolveWithCurrentEE(MavenSession session, MavenProject project, String newEEName) {
        TargetPlatformConfiguration config = (TargetPlatformConfiguration) project
                .getContextValue(TychoConstants.CTX_TARGET_PLATFORM_CONFIGURATION);
        config.setExecutionEnvironment(newEEName);

        ExecutionEnvironmentConfiguration sink = new ExecutionEnvironmentConfigurationImpl(logger,
                !config.isResolveWithEEConstraints());
        sink.overrideProfileConfiguration(newEEName, "current execution environment");

        project.setContextValue(TychoConstants.CTX_EXECUTION_ENVIRONMENT_CONFIGURATION, sink);
        List<ReactorProject> reactorProjects = DefaultReactorProject.adapt(session);
        DependencyResolverConfiguration resolverConfiguration = config.getDependencyResolverConfiguration();
        DependencyResolver depResolver = dependencyResolverLocator.lookupDependencyResolver(project);
        TargetPlatform preliminaryTargetPlatform = depResolver.computePreliminaryTargetPlatform(session, project,
                reactorProjects);
        return depResolver.resolveDependencies(session, project, preliminaryTargetPlatform, reactorProjects,
                resolverConfiguration);
    }

}
