/*
 * The MIT License
 *
 * Copyright (c) 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package jenkins.plugins.git;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import jenkins.scm.api.SCMFile;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Implementation of {@link SCMFile} for Git.
 *
 * @since FIXME
 */
public class GitSCMFile extends SCMFile {

    private final GitSCMFileSystem fs;

    public GitSCMFile(GitSCMFileSystem fs) {
        this.fs = fs;
    }

    public GitSCMFile(GitSCMFileSystem fs, @NonNull GitSCMFile parent, String name) {
        super(parent, name);
        this.fs = fs;
    }

    @NonNull
    @Override
    protected SCMFile newChild(String name, boolean assumeIsDirectory) {
        return new GitSCMFile(fs, this, name);
    }

    @NonNull
    @Override
    public Iterable<SCMFile> children() throws IOException, InterruptedException {
        return fs.invoke(new GitSCMFileSystem.FSFunction<List<SCMFile>>() {
            @Override
            public List<SCMFile> invoke(Repository repository) throws IOException, InterruptedException {
                RevWalk walk = new RevWalk(repository);
                try {
                    RevCommit commit = walk.parseCommit(fs.getCommitId());
                    RevTree tree = commit.getTree();
                    TreeWalk tw;
                    if (isRoot()) {
                        tw = new TreeWalk(repository);
                        tw.addTree(tree);
                        tw.setRecursive(false);
                        try {
                            List<SCMFile> result = new ArrayList<SCMFile>();
                            while (tw.next()) {
                                result.add(new GitSCMFile(fs, GitSCMFile.this, tw.getNameString()));
                            }
                            return result;
                        } finally {
                            AbstractGitSCMSource._release(tw);
                        }
                    } else {
                        tw = TreeWalk.forPath(repository, getPath(), tree);
                        if (tw == null) {
                            throw new FileNotFoundException();
                        }
                        try {
                            FileMode fileMode = tw.getFileMode(0);
                            if (fileMode == FileMode.MISSING) {
                                throw new FileNotFoundException();
                            }
                            if (fileMode != FileMode.TREE) {
                                throw new IOException("Not a directory");
                            }
                            tw.enterSubtree();
                            List<SCMFile> result = new ArrayList<SCMFile>();
                            while (tw.next()) {
                                result.add(new GitSCMFile(fs, GitSCMFile.this, tw.getNameString()));
                            }
                            return result;
                        } finally {
                            AbstractGitSCMSource._release(tw);
                        }
                    }
                } finally {
                    AbstractGitSCMSource._release(walk);
                }
            }
        });
    }

    @Override
    public long lastModified() throws IOException, InterruptedException {
        // TODO a more correct implementation
        return fs.lastModified();
    }

    @NonNull
    @Override
    protected Type type() throws IOException, InterruptedException {
        if (isRoot()) {
            // special-case
            return Type.DIRECTORY;
        }
        return fs.invoke(new GitSCMFileSystem.FSFunction<Type>() {
            @Override
            public Type invoke(Repository repository) throws IOException, InterruptedException {
                RevWalk walk = new RevWalk(repository);
                try {
                    RevCommit commit = walk.parseCommit(fs.getCommitId());
                    RevTree tree = commit.getTree();
                    TreeWalk tw = TreeWalk.forPath(repository, getPath(), tree);
                    try {
                        if (tw == null) {
                            return SCMFile.Type.NONEXISTENT;
                        }
                        FileMode fileMode = tw.getFileMode(0);
                        if (fileMode == FileMode.MISSING) {
                            return SCMFile.Type.NONEXISTENT;
                        }
                        if (fileMode == FileMode.EXECUTABLE_FILE) {
                            return SCMFile.Type.REGULAR_FILE;
                        }
                        if (fileMode == FileMode.REGULAR_FILE) {
                            return SCMFile.Type.REGULAR_FILE;
                        }
                        if (fileMode == FileMode.SYMLINK) {
                            return SCMFile.Type.LINK;
                        }
                        if (fileMode == FileMode.TREE) {
                            return SCMFile.Type.DIRECTORY;
                        }
                        return SCMFile.Type.OTHER;
                    } finally {
                        AbstractGitSCMSource._release(tw);
                    }
                } finally {
                    AbstractGitSCMSource._release(walk);
                }
            }
        });
    }

    @NonNull
    @Override
    public InputStream content() throws IOException, InterruptedException {
        return fs.invoke(new GitSCMFileSystem.FSFunction<InputStream>() {
            @Override
            public InputStream invoke(Repository repository) throws IOException, InterruptedException {
                RevWalk walk = new RevWalk(repository);
                try {
                    RevCommit commit = walk.parseCommit(fs.getCommitId());
                    RevTree tree = commit.getTree();
                    TreeWalk tw = TreeWalk.forPath(repository, getPath(), tree);
                    try {
                        if (tw == null) {
                            throw new FileNotFoundException();
                        }
                        FileMode fileMode = tw.getFileMode(0);
                        if (fileMode == FileMode.MISSING) {
                            throw new FileNotFoundException();
                        }
                        if (fileMode == FileMode.TREE) {
                            throw new IOException("Directory");
                        }
                        ObjectId objectId = tw.getObjectId(0);
                        ObjectLoader loader = repository.open(objectId);
                        return new ByteArrayInputStream(loader.getBytes());
                    } finally {
                        AbstractGitSCMSource._release(tw);
                    }
                } finally {
                    AbstractGitSCMSource._release(walk);
                }
            }
        });
    }
}