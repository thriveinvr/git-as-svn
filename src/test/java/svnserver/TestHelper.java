/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Repository;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Common test functions.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class TestHelper {
  public static void saveFile(@NotNull File file, @NotNull String content) throws IOException {
    try (OutputStream stream = new FileOutputStream(file)) {
      stream.write(content.getBytes(StandardCharsets.UTF_8));
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  public static File createTempDir(@NotNull String prefix) throws IOException {
    final File dir = File.createTempFile(prefix, "");
    dir.delete();
    dir.mkdir();
    return dir;
  }

  public static Repository emptyRepository() throws IOException {
    final Repository repository = new InMemoryRepository(new DfsRepositoryDescription(null));
    repository.create();
    return repository;
  }
}