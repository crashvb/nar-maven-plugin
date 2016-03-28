/*
 * #%L
 * Native ARchive plugin for Maven
 * %%
 * Copyright (C) 2002 - 2014 NAR Maven Plugin developers.
 * %%
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
 * #L%
 */
package com.github.maven_nar;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.artifact.filter.collection.ScopeFilter;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;

import com.github.maven_nar.cpptasks.CCTask;
import com.github.maven_nar.cpptasks.CUtil;
import com.github.maven_nar.cpptasks.CompilerDef;
import com.github.maven_nar.cpptasks.LinkerDef;
import com.github.maven_nar.cpptasks.OutputTypeEnum;
import com.github.maven_nar.cpptasks.RuntimeType;
import com.github.maven_nar.cpptasks.SubsystemEnum;
import com.github.maven_nar.cpptasks.ide.ProjectDef;
import com.github.maven_nar.cpptasks.ide.ProjectWriterEnum;
import com.github.maven_nar.cpptasks.types.DefineArgument;
import com.github.maven_nar.cpptasks.types.DefineSet;
import com.github.maven_nar.cpptasks.types.LibrarySet;
import com.github.maven_nar.cpptasks.types.LinkerArgument;
import com.github.maven_nar.cpptasks.types.SystemLibrarySet;

/**
 * Generates a Visual Studio 2005 project file (vcproj) Heavily inspired by
 * NarCompileMojo.
 *
 * @author Darren Sargent
 *
 */
@Mojo(name = "nar-vcproj", defaultPhase = LifecyclePhase.GENERATE_RESOURCES,
  requiresDependencyResolution = ResolutionScope.COMPILE)
public class NarVcprojMojo extends AbstractCompileMojo {

  // FIXME: code duplication with NarCompileMojo
  private void createVcProjFile(final Project antProject, final Library library)
      throws MojoExecutionException, MojoFailureException {

    // configure task
    final CCTask task = new CCTask();
    task.setProject(antProject);
    task.setDebug(true);

    // subsystem (console)
    final SubsystemEnum subSystem = new SubsystemEnum();
    subSystem.setValue(library.getSubSystem());
    task.setSubsystem(subSystem);

    // outtype
    final OutputTypeEnum outTypeEnum = new OutputTypeEnum();
    final String type = library.getType();
    outTypeEnum.setValue(type);
    task.setOuttype(outTypeEnum);

    // stdc++
    task.setLinkCPP(library.linkCPP());

    // TODO: this should match the standard NAR location defined by layout
    // similar to Nar Compile
    // outDir
    File outDir = new File(getTargetDirectory(), "bin");
    outDir = new File(outDir, getAOL().toString());
    outDir.mkdirs();

    // outFile
    final File outFile = new File(outDir, getOutput(getAOL(), type));

    getLog().debug("NAR - output: '" + outFile + "'");
    task.setOutfile(outFile);

    // object directory
    File objDir = new File(getTargetDirectory(), "obj");
    objDir = new File(objDir, getAOL().toString());
    objDir.mkdirs();
    task.setObjdir(objDir);

    // failOnError, libtool
    task.setFailonerror(failOnError(getAOL()));
    task.setLibtool(useLibtool(getAOL()));

    // runtime
    final RuntimeType runtimeType = new RuntimeType();
    runtimeType.setValue(getRuntime(getAOL()));
    task.setRuntime(runtimeType);

    // add C++ compiler
    final CompilerDef cpp = getCpp().getCompiler(Compiler.MAIN, null);
    if (cpp != null) {
      task.addConfiguredCompiler(cpp);
    }

    // add VCPROJ_MOJO def (see UnitTestDriverImpl.cpp generated by Krusoe
    // plugin)
    final DefineSet defineSet = new DefineSet();
    final DefineArgument defineArgument = new DefineArgument();
    defineArgument.setName("VCPROJ_MOJO");
    defineSet.addDefine(defineArgument);
    cpp.addConfiguredDefineset(defineSet);

    // add javah include path
    final File jniDirectory = getJavah().getJniDirectory();
    if (jniDirectory.exists()) {
      task.createIncludePath().setPath(jniDirectory.getPath());
    }

    // add java include paths
    getJava().addIncludePaths(task, Library.EXECUTABLE);

    getMsvc().configureCCTask(task);

    final List<NarArtifact> dependencies = getNarArtifacts();
    // add dependency include paths
    for (final Object element : dependencies) {
      // FIXME, handle multiple includes from one NAR
      final NarArtifact narDependency = (NarArtifact) element;
      final String binding = narDependency.getNarInfo().getBinding(getAOL(), Library.STATIC);
      getLog().debug("Looking for " + narDependency + " found binding " + binding);
      if (!binding.equals(Library.JNI)) {
        final File unpackDirectory = getUnpackDirectory();
        final File include = getLayout().getIncludeDirectory(unpackDirectory, narDependency.getArtifactId(),
            narDependency.getBaseVersion());
        getLog().debug("Looking for directory: " + include);
        if (include.exists()) {
          task.createIncludePath().setPath(include.getPath());
        } else {
          getLog().warn(String.format("Unable to locate %1$s lib include path '%2$s'", binding, include));
        }
      }
    }

    // add linker
    final LinkerDef linkerDefinition = getLinker().getLinker(this, task, getOS(), getAOL().getKey() + ".linker.",
        type);
    task.addConfiguredLinker(linkerDefinition);

    // add dependency libraries
    // FIXME: what about PLUGIN and STATIC, depending on STATIC, should we
    // not add all libraries, see NARPLUGIN-96
    if (type.equals(Library.SHARED) || type.equals(Library.JNI) || type.equals(Library.EXECUTABLE)) {

      final List depLibOrder = getDependencyLibOrder();
      List depLibs = dependencies;

      // reorder the libraries that come from the nar dependencies
      // to comply with the order specified by the user
      if (depLibOrder != null && !depLibOrder.isEmpty()) {

        final List tmp = new LinkedList();

        for (final Iterator i = depLibOrder.iterator(); i.hasNext();) {

          final String depToOrderName = (String) i.next();

          for (final Iterator j = depLibs.iterator(); j.hasNext();) {

            final NarArtifact dep = (NarArtifact) j.next();
            final String depName = dep.getGroupId() + ":" + dep.getArtifactId();

            if (depName.equals(depToOrderName)) {

              tmp.add(dep);
              j.remove();
            }
          }
        }

        tmp.addAll(depLibs);
        depLibs = tmp;
      }

      for (final Iterator i = depLibs.iterator(); i.hasNext();) {

        final NarArtifact dependency = (NarArtifact) i.next();

        // FIXME no handling of "local"

        // FIXME, no way to override this at this stage
        final String binding = dependency.getNarInfo().getBinding(getAOL(), Library.STATIC);
        getLog().debug("Using Binding: " + binding);
        AOL aol = getAOL();
        aol = dependency.getNarInfo().getAOL(getAOL());
        getLog().debug("Using Library AOL: " + aol.toString());

        if (!binding.equals(Library.JNI) && !binding.equals(Library.NONE) && !binding.equals(Library.EXECUTABLE)) {
          final File unpackDirectory = getUnpackDirectory();
          final File dir = getLayout().getLibDirectory(unpackDirectory, dependency.getArtifactId(),
              dependency.getBaseVersion(), aol.toString(), binding);
          getLog().debug("Looking for Library Directory: " + dir);
          if (dir.exists()) {
            final LibrarySet libSet = new LibrarySet();
            libSet.setProject(antProject);

            // FIXME, no way to override
            final String libs = dependency.getNarInfo().getLibs(getAOL());
            if (libs != null && !libs.equals("")) {
              getLog().debug("Using LIBS = " + libs);
              libSet.setLibs(new CUtil.StringArrayBuilder(libs));
              libSet.setDir(dir);
              task.addLibset(libSet);
            }
          } else {
            getLog().debug("Library Directory " + dir + " does NOT exist.");
          }

          // FIXME, look again at this, for multiple dependencies we
          // may need to remove duplicates
          final String options = dependency.getNarInfo().getOptions(getAOL());
          if (options != null && !options.equals("")) {
            getLog().debug("Using OPTIONS = " + options);
            final LinkerArgument arg = new LinkerArgument();
            arg.setValue(options);
            linkerDefinition.addConfiguredLinkerArg(arg);
          }

          final String sysLibs = dependency.getNarInfo().getSysLibs(getAOL());

          if (sysLibs != null && !sysLibs.equals("")) {
            getLog().debug("Using SYSLIBS = " + sysLibs);
            final SystemLibrarySet sysLibSet = new SystemLibrarySet();
            sysLibSet.setProject(antProject);

            sysLibSet.setLibs(new CUtil.StringArrayBuilder(sysLibs));

            task.addSyslibset(sysLibSet);
          }
        }
      }

      // Add JVM to linker
      getJava().addRuntime(task, getJavaHome(getAOL()), getOS(), getAOL().getKey() + "java.");

      // DS: generate project file
      getLog().debug("NAR: Writing project file...");
      final ProjectWriterEnum projectWriterEnum = new ProjectWriterEnum();
      projectWriterEnum.setValue("msvc8");
      final ProjectDef projectDef = new ProjectDef();
      projectDef.setType(projectWriterEnum);
      String filename = null;
      try {
        final File outputDir = new File(getTargetDirectory(), "vcproj");
        if (!outputDir.exists()) {
          final boolean succeeded = outputDir.mkdir();
          if (!succeeded) {
            throw new MojoExecutionException("Unable to create directory: " + outputDir);
          }
        }
        filename = outputDir + "/" + getMavenProject().getArtifactId();
        final File projFile = new File(filename);
        projectDef.setOutfile(projFile.getCanonicalFile());
      } catch (final IOException e) {
        throw new MojoExecutionException("Unable to create file: " + filename, e);
      }
      task.addProject(projectDef);
      task.setProjectsOnly(true);

      // we always want an EXE for debugging
      task.setOuttype(new OutputTypeEnum());

      // execute
      try {
        task.execute();
        getLog().info("Wrote project file: " + filename + ".vcproj");
      } catch (final BuildException e) {
        throw new MojoExecutionException("NAR: Compile failed", e);
      }
    }
  }

  /**
   * List the dependencies needed for compilation, those dependencies are used
   * to get the include paths needed for
   * compilation and to get the libraries paths and names needed for linking.
   */
  @Override
  protected ScopeFilter getArtifactScopeFilter() {
    return new ScopeFilter( Artifact.SCOPE_COMPILE, null );
  }

  @Override
  public void narExecute() throws MojoExecutionException, MojoFailureException {

    // Only do this if MSVC++ compiler is being used.
    if (!getOS().equals(OS.WINDOWS)) {
      getLog().debug("Skipping -- not running on Windows");
      return;
    }

    // need to run with profile "windows-debug". No other profiles are valid
    // for vcproj generation.
    boolean debug = false;

    final List profiles = NarUtil.collectActiveProfiles(getMavenProject());
    for (final Iterator i = profiles.iterator(); i.hasNext();) {
      final org.apache.maven.model.Profile profile = (org.apache.maven.model.Profile) i.next();
      if (profile.getId().equalsIgnoreCase("windows-debug")) {
        debug = true;
        break;
      }
    }

    if (!debug) {
      getLog().info("NAR: Skipping vcproj generation.  Run with -P windows-debug to enable this step.");
      return;
    }

    if (getLibraries().isEmpty()) {
      getLog().info("NAR: Skipping vcproj generation.  No libraries to be built.");
      return;
    }

    // super.narExecute();

    // arbitrarily grab the first library -- we're going to make treat it as
    // an exe anyway, whatever type it's supposed to be.
    createVcProjFile(getAntProject(), getLibraries().get(0));
  }
}
