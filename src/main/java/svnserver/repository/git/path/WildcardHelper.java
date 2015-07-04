/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git.path;

import org.eclipse.jgit.errors.InvalidPatternException;
import org.jetbrains.annotations.NotNull;
import svnserver.repository.git.path.matcher.ComplexMatcher;
import svnserver.repository.git.path.matcher.EqualsMatcher;
import svnserver.repository.git.path.matcher.RecursiveMatcher;
import svnserver.repository.git.path.matcher.SimpleMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;

/**
 * Git wildcard mask.
 * <p>
 * Pattern format: http://git-scm.com/docs/gitignore
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class WildcardHelper {
  public static final char PATH_SEPARATOR = '/';

  private static final boolean DEBUG_WILDCARD = true;

  @NotNull
  public static NameMatcher nameMatcher(@NotNull String mask) throws InvalidPatternException {
    if (mask.equals("**/")) {
      return RecursiveMatcher.INSTANCE;
    }
    final boolean dirOnly = mask.endsWith("/");
    final String nameMask = dirOnly ? mask.substring(0, mask.length() - 1) : mask;
    if ((mask.indexOf('[') < 0) && (mask.indexOf(']') < 0) && (mask.indexOf('\\') < 0)) {
      // Subversion compatible mask.
      if (mask.indexOf('?') < 0) {
        int asterisk = mask.indexOf('*');
        if (asterisk < 0) {
          return new EqualsMatcher(nameMask, dirOnly);
        } else if (mask.indexOf('*', asterisk + 1) < 0) {
          return new SimpleMatcher(nameMask.substring(0, asterisk), nameMask.substring(asterisk + 1), dirOnly);
        }
      }
      return new ComplexMatcher(nameMask, dirOnly, true);
    } else {
      return new ComplexMatcher(nameMask, dirOnly, false);
    }
  }

  /**
   * Split pattern with saving slashes.
   *
   * @param pattern Path pattern.
   * @return Path pattern items.
   */
  public static List<String> splitPattern(@NotNull String pattern) {
    final List<String> result = new ArrayList<>(count(pattern, PATH_SEPARATOR) + 1);
    int start = 0;
    while (true) {
      int next = pattern.indexOf(PATH_SEPARATOR, start);
      if (next == -1) {
        if (start < pattern.length()) {
          result.add(pattern.substring(start));
        }
        break;
      }
      result.add(pattern.substring(start, next + 1));
      start = next + 1;
    }
    return result;
  }

  /**
   * Remove redundant pattern parts and make patterns more simple.
   *
   * @param tokens Original modifiable list.
   * @return Return tokens,
   */
  public static List<String> normalizePattern(@NotNull List<String> tokens) {
    // Add "any path" prefix for simple mask
    if (tokens.size() == 1 && !tokens.get(0).endsWith("/")) {
      tokens.add(0, "**/");
    }
    if (tokens.size() == 0 || !tokens.get(0).equals("/")) {
      tokens.add(0, "/");
    }
    ListIterator<String> iter = tokens.listIterator();
    String prev = null;
    while (iter.hasNext()) {
      if (DEBUG_WILDCARD) {
        final String checkPrev;
        if (iter.hasPrevious()) {
          checkPrev = iter.previous();
          iter.next();
        } else {
          checkPrev = null;
        }
        assert (Objects.equals(prev, checkPrev));
      }

      final String token = iter.next();
      if ("**/".equals(prev)) {
        if (token.equals("*/")) {
          iter.previous();
          iter.previous();
          iter.set(token);
          iter.next();
          iter.next();
          iter.set(prev);
          continue;
        }
        if (token.equals("*") || token.equals("**")) {
          iter.previous();
          iter.previous();
          iter.remove();
          assert (iter.hasPrevious());
          prev = iter.previous();
          iter.next();
          continue;
        }
      }
      if (token.equals("**")) {
        iter.remove();
        continue;
      }
      if (token.equals("**/")) {
        if ("**/".equals(prev)) {
          iter.remove();
        }
        prev = token;
        continue;
      }
      // Convert "**.test" to "**/" + "*.test"
      if (token.startsWith("**")) {
        iter.remove();
        iter.add("**/");
        iter.add(token.substring(1));
        iter.previous();
        iter.previous();
        continue;
      }
      prev = token;
    }
    return tokens;
  }

  public static int count(@NotNull String s, char c) {
    int start = 0;
    int count = 0;
    while (true) {
      start = s.indexOf(c, start);
      if (start == -1)
        break;
      count++;
      start++;
    }
    return count;
  }
}
