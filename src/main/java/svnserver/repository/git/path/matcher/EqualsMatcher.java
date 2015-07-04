/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git.path.matcher;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import svnserver.repository.git.path.NameMatcher;

/**
 * Simple matcher for equals compare.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class EqualsMatcher implements NameMatcher {
  @NotNull
  private final String name;
  private final boolean dirOnly;

  public EqualsMatcher(@NotNull String name, boolean dirOnly) {
    this.name = name;
    this.dirOnly = dirOnly;
  }

  @Override
  public boolean isMatch(@NotNull String name, boolean isDir) {
    return (!dirOnly || isDir) && this.name.equals(name);
  }

  @Override
  public boolean isRecursive() {
    return false;
  }

  @Nullable
  @Override
  public String getSvnMask() {
    return name;
  }
}
