package org.apache.maven.plugin.reactor;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.Invoker;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.dag.DAG;
import org.codehaus.plexus.util.dag.Vertex;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Goal to build a project X and all of the reactor projects on which X depends 
 *
 * @author <a href="mailto:dfabulich@apache.org">Dan Fabulich</a>
 */
@Mojo( name = "make", aggregator = true, defaultPhase = LifecyclePhase.PROCESS_SOURCES )
public class MakeMojo
    extends AbstractMojo
{
    /**
     * Location of the POM file; provided by Maven
     */
    @Parameter( property = "basedir" )
    File baseDir;

    /**
     * A list of every project in this reactor; provided by Maven
     */
    @Parameter( property = "project.collectedProjects" )
    List collectedProjects;

    /**
     * If you don't specify a groupId in your artifactList, we'll use this as the default groupId.
     */
    @Parameter( property = "make.group", defaultValue = "${project.groupId}" )
    String defaultGroup;

    /**
     * A list of artifacts to build, e.g. "com.mycompany:bar,com.mycompany:foo" or just "foo,bar", or just "foo"
     */
    @Parameter( property = "make.artifacts", defaultValue = "", required = true )
    String artifactList;

    /**
     * A list of relative paths to build, e.g. "foo,baz/bar"
     */
    @Parameter( property = "make.folders", defaultValue = "", required = true )
    String folderList;

    /**
     * Goals to run on subproject.
     */
    @Parameter( property = "make.goals", defaultValue = "install" )
    String goals;

    /**
     * Provided by Maven
     */
    @Component
    Invoker invoker;

    /**
     * Don't really do anything; just print a command that describes what the command would have done
     */
    @Parameter( property = "make.printOnly" )
    private boolean printOnly = false;

    /**
     */
    @Component
    SimpleInvoker simpleInvoker;

    /**
     * The artifact from which we'll resume, e.g. "com.mycompany:foo" or just "foo"
     */
    @Parameter( property = "fromArtifact" )
    String continueFromProject;

    /**
     * The project folder from which we'll resume
     */
    @Parameter( property = "from" )
    File continueFromFolder;
    
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( artifactList == null && folderList == null ) {
            throw new MojoFailureException("You must specify either folders or projects with -Dmake.folders=foo,baz/bar or -Dmake.artifacts=com.mycompany:foo,com.mycompany:bar");
        }
        String[] reactorIncludes;
        List sortedProjects;
        try
        {
            if (collectedProjects.size() == 0) {
                throw new NonReactorException();
            }
            SuperProjectSorter ps = new SuperProjectSorter( collectedProjects );
            DAG dag = ps.getDAG();
            
            // gather projects
            collectArtifactListFromFolderList( collectedProjects );
            String[] artifacts = StringUtils.split( artifactList, "," );
            Set visited = new HashSet();
            Set out = new HashSet();
            for (String artifact : artifacts) {
                String project = artifact;
                if (project.indexOf(':') == -1) {
                    project = defaultGroup + ":" + project;
                }
                Vertex projectVertex = dag.getVertex(project);
                if (projectVertex == null) throw new MissingProjectException(project);
                gatherProjects(projectVertex, ps, visited, out);
            }
            
            // sort them again
            ps = new SuperProjectSorter( new ArrayList( out ) );
            sortedProjects = ps.getSortedProjects();
            
            // construct array of relative POM paths
            reactorIncludes = new String[sortedProjects.size()];
            for ( int i = 0; i < sortedProjects.size(); i++ )
            {
                MavenProject mp = (MavenProject) sortedProjects.get( i );
                String path = RelativePather.getRelativePath( baseDir, mp.getFile() );
                reactorIncludes[i] = path;
            }
        }
        catch (MojoFailureException e) {
            throw e;
        }        
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Problem generating dependency tree", e );
        }

        if (continueFromFolder != null || continueFromProject != null) {
            ResumeMojo resumer = new ResumeMojo();
            resumer.baseDir = baseDir;
            resumer.collectedProjects = sortedProjects;
            resumer.continueFromFolder = continueFromFolder;
            resumer.continueFromProject = continueFromProject;
            resumer.goals = goals;
            resumer.invoker = invoker;
            resumer.simpleInvoker = simpleInvoker;
            resumer.printOnly = printOnly;
            resumer.continueFromGroup = defaultGroup;
            resumer.execute();
        } else {
            simpleInvoker.runReactor( reactorIncludes, Arrays.asList( goals.split( "," ) ), invoker, printOnly, getLog() );
        }

    }

    void collectArtifactListFromFolderList(List collectedProjects) throws MojoFailureException
    {
        if ( folderList == null )
            return;
        String[] folders = StringUtils.split( folderList, "," );
        Set pathSet = new HashSet();
        for (String folder : folders) {
            File file = new File(baseDir, folder);
            if (!file.exists()) {
                throw new MojoFailureException("Folder doesn't exist: " + file.getAbsolutePath());
            }
            String path = file.getAbsolutePath();
            pathSet.add(path);
        }
        if (artifactList == null) artifactList = "";
        StringBuilder artifactBuffer = new StringBuilder(artifactList);
        for (Object collectedProject : collectedProjects) {
            MavenProject mp = (MavenProject) collectedProject;
            if (pathSet.contains(mp.getFile().getParentFile().getAbsolutePath())) {
                if (artifactBuffer.length() > 0) {
                    artifactBuffer.append(',');
                }
                String id = ArtifactUtils.versionlessKey(mp.getGroupId(), mp.getArtifactId());
                artifactBuffer.append(id);
            }
        }
        if ( artifactBuffer.length() == 0 )
        {
            throw new MojoFailureException("No folders matched: " + folderList);
        }
        artifactList = artifactBuffer.toString();
    }

    protected Set gatherProjects( Vertex v, SuperProjectSorter ps, Set visited, Set out )
    {
        visited.add( v );
        out.add( ps.getProjectMap().get( v.getLabel() ) );
        List children = v.getChildren();
        for (Object aChildren : children) {
            Vertex child = (Vertex) aChildren;
            if (visited.contains(child))
                continue;
            gatherProjects(child, ps, visited, out);
        }
        return out;
    }
}
