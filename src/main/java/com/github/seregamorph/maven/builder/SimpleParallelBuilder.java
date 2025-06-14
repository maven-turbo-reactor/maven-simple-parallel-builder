package com.github.seregamorph.maven.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.BuildThreadFactory;
import org.apache.maven.lifecycle.internal.LifecycleModuleBuilder;
import org.apache.maven.lifecycle.internal.ProjectBuildList;
import org.apache.maven.lifecycle.internal.ProjectSegment;
import org.apache.maven.lifecycle.internal.ReactorBuildStatus;
import org.apache.maven.lifecycle.internal.ReactorContext;
import org.apache.maven.lifecycle.internal.TaskSegment;
import org.apache.maven.lifecycle.internal.builder.Builder;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.Logger;

/**
 * Based on simplified code from
 * {@link org.apache.maven.lifecycle.internal.builder.multithreaded.MultiThreadedBuilder}.
 * <p>
 * Executes the build with maximum parallelism, modules are not waiting for all dependencies to be resolved as it's
 * assumed they are already built in the previous execution.
 * It supports optional configuration with prioritized modules, see module readme.
 *
 * @author Sergey Chernov
 */
@Singleton
@Named("simple-parallel")
public class SimpleParallelBuilder implements Builder {

    private final LifecycleModuleBuilder lifecycleModuleBuilder;
    private final Logger logger;

    @Inject
    public SimpleParallelBuilder(LifecycleModuleBuilder lifecycleModuleBuilder, Logger logger) {
        this.lifecycleModuleBuilder = lifecycleModuleBuilder;
        this.logger = logger;
    }

    @Override
    public void build(
        MavenSession session,
        ReactorContext reactorContext,
        ProjectBuildList projectBuilds,
        List<TaskSegment> taskSegments,
        ReactorBuildStatus reactorBuildStatus
    ) throws InterruptedException {
        SimpleParallelConfig config = getConfig(session);

        int nThreads = Math.min(
            session.getRequest().getDegreeOfConcurrency(),
            session.getProjects().size());
        logger.info("SimpleParallelBuilder will use " + nThreads + " threads to build "
            + session.getProjects().size() + " modules");
        boolean parallel = nThreads > 1;
        // Propagate the parallel flag to the root session and all of the cloned sessions in each project segment
        session.setParallel(parallel);
        for (ProjectSegment segment : projectBuilds) {
            segment.getSession().setParallel(parallel);
        }
        ExecutorService executor = Executors.newFixedThreadPool(nThreads, new BuildThreadFactory());
        CompletionService<ProjectSegment> service = new ExecutorCompletionService<>(executor);

        for (TaskSegment taskSegment : taskSegments) {
            ProjectBuildList segmentProjectBuilds = projectBuilds.getByTaskSegment(taskSegment);
            Map<MavenProject, ProjectSegment> projectBuildMap = projectBuilds.selectSegment(taskSegment);
            try {
                multiThreadedProjectTaskSegmentBuild(config.getPrioritizedModules(),
                    segmentProjectBuilds, reactorContext, session, service,
                    taskSegment, projectBuildMap);
                if (reactorContext.getReactorBuildStatus().isHalted()) {
                    break;
                }
            } catch (Exception e) {
                session.getResult().addException(e);
                break;
            }
        }

        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }

    private void multiThreadedProjectTaskSegmentBuild(
        List<String> prioritizedModules,
        ProjectBuildList segmentProjectBuilds,
        ReactorContext reactorContext,
        MavenSession rootSession,
        CompletionService<ProjectSegment> service,
        TaskSegment taskSegment,
        Map<MavenProject, ProjectSegment> projectBuildList
    ) {
        // gather artifactIds which are not unique so that the respective thread names can be extended with the groupId
        Set<String> duplicateArtifactIds = gatherDuplicateArtifactIds(projectBuildList.keySet());

        // schedule independent projects
        List<Future<?>> futures = new ArrayList<>();

        List<MavenProject> projects = segmentProjectBuilds.getProjects().stream()
            .sorted(Comparator.comparing((MavenProject p) -> {
                String groupArtifactId = p.getGroupId() + ":" + p.getArtifactId();
                return prioritizedModules.contains(groupArtifactId)
                    ? prioritizedModules.indexOf(groupArtifactId) : prioritizedModules.size();
            }))
            .collect(Collectors.toList());

        for (MavenProject mavenProject : projects) {
            ProjectSegment projectSegment = projectBuildList.get(mavenProject);
            logger.debug("Scheduling: " + projectSegment.getProject());
            Callable<ProjectSegment> cb = () -> buildModule(rootSession, projectSegment, reactorContext,
                taskSegment, duplicateArtifactIds);
            futures.add(service.submit(cb));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
                if (reactorContext.getReactorBuildStatus().isHalted()) {
                    break;
                }
            } catch (InterruptedException | ExecutionException e) {
                rootSession.getResult().addException(e);
                break;
            }
        }
    }

    private ProjectSegment buildModule(
        MavenSession rootSession,
        ProjectSegment projectBuild,
        ReactorContext reactorContext,
        TaskSegment taskSegment,
        Set<String> duplicateArtifactIds
    ) {
        Thread currentThread = Thread.currentThread();
        String originalThreadName = currentThread.getName();
        MavenProject project = projectBuild.getProject();

        String threadNameSuffix = duplicateArtifactIds.contains(project.getArtifactId())
            ? project.getGroupId() + ":" + project.getArtifactId()
            : project.getArtifactId();
        currentThread.setName("simple-builder-" + threadNameSuffix);

        try {
            lifecycleModuleBuilder.buildProject(
                projectBuild.getSession(), rootSession, reactorContext, project, taskSegment);
            return projectBuild;
        } finally {
            currentThread.setName(originalThreadName);
        }
    }

    private SimpleParallelConfig getConfig(MavenSession session) {
        File configFile = new File(session.getExecutionRootDirectory(), ".mvn/simple-parallel.json");
        if (configFile.exists()) {
            logger.info("Loading config from " + configFile);
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                return objectMapper.readValue(configFile, SimpleParallelConfig.class);
            } catch (IOException e) {
                throw new UncheckedIOException("Error while reading " + configFile, e);
            }
        }
        return new SimpleParallelConfig();
    }

    private static Set<String> gatherDuplicateArtifactIds(Set<MavenProject> projects) {
        Set<String> artifactIds = new HashSet<>(projects.size());
        Set<String> duplicateArtifactIds = new HashSet<>();
        for (MavenProject project : projects) {
            if (!artifactIds.add(project.getArtifactId())) {
                duplicateArtifactIds.add(project.getArtifactId());
            }
        }
        return duplicateArtifactIds;
    }
}
