;; Leiningen Less CSS compile task.
;; Copyright 2012 Fabio Mancinelli <fabio@mancinelli.me>
;;
;; Contributors:
;;   John Szakmeister <john@szakmeister.net>
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program. If not, see <http://www.gnu.org/licenses/>

(ns leiningen.lesscss
  (:require [leiningen.core.main :as main]
            [leiningen.lesscss.file-utils :refer [list-less-files to-file replace-extension]]
            [leiningen.core.classpath :as cp]
            [clojure.string :refer [join]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [classlojure.core :as cl])
  (:import (com.asual.lesscss LessEngine LessException LessOptions)
           (com.asual.lesscss.loader ChainedResourceLoader
                                     ClasspathResourceLoader
                                     FilesystemResourceLoader
                                     HTTPResourceLoader
                                     ResourceLoader
                                     JNDIResourceLoader
                                     UnixNewlinesResourceLoader)))

(defn lesscss-compiler
  "Create an instance of the Less CSS compiler."
  [project]
  (LessEngine. (LessOptions.)
               (UnixNewlinesResourceLoader.
                 (let [loaders [(FilesystemResourceLoader.)
                                (ClasspathResourceLoader.
                                  (->> (cp/get-classpath project)
                                       (map io/file)
                                       cl/classlojure))
                                (JNDIResourceLoader.)
                                (HTTPResourceLoader.)]]
                   (reify
                     ResourceLoader

                     (^boolean exists [_ ^String resource #^"[Ljava.lang.String;" paths]
                       (loop [loaders loaders]
                         (if (empty? loaders)
                           false
                           (or (.exists (first loaders) resource paths)
                               (recur (rest loaders))))))

                     (^String load [_ ^String resource #^"[Ljava.lang.String;" paths ^String charset]
                       (loop [loaders loaders]
                         (if (empty? loaders)
                           (throw (java.io.IOException. (str "No such file " resource)))
                           (or (and (.exists (first loaders) resource paths)
                                    (.load (first loaders) resource paths charset))
                               (recur (rest loaders)))))))))))

(defn default-lesscss-paths
  "Return a list containing a single path where Less files are stored."
  [project]
  (cons (org.apache.commons.io.FilenameUtils/normalize  (str (:root project) "/less")) nil))

(defn get-output-file
  "Get the file where to store the compiled output. Its path will depend on the relative path in the source tree.
  For example, if the path where less files are stored is '/.../projectdir/less/', the current less file is
  '/.../projectdir/less/foo/bar.less' and the output path is '/.../projectdir/target/classes' then
  the output path will be '/.../projectdir/target/classes/foo/bar.less'"
  [base-path file output-path]
  (let [relative-path (clojure.string/replace (.getAbsolutePath file) base-path "")]
    (to-file
      (replace-extension
        (org.apache.commons.io.FilenameUtils/normalize (str output-path "/" relative-path))
        "css"))))

(defn lesscss-compile
  "Compile the Less CSS file to the specified output file."
  [project prefix less-file output-path]
  (let [output-file (get-output-file prefix less-file output-path)]
    (try
      (when (or (not (.exists output-file))
                (> (.lastModified less-file) (.lastModified output-file)))
        (.compile (lesscss-compiler project) less-file output-file))
      (catch LessException e
        (println "ERROR: compiling " less-file ": " (.getMessage e))))))

(defn watch-less-files
  "Watch for changes in the less files and recompile on change"
  [project lesscss-path lesscss-output-path]
  (println "Watching " lesscss-path)
  (watcher/watcher [lesscss-path]
    (watcher/rate 50)
    (watcher/file-filter (watcher/extensions :less))
    (watcher/on-change (fn [changes]
                         (doseq [c changes]
                           (println "Compiling" (.getPath c))
                           (lesscss-compile project lesscss-path c lesscss-output-path))))))


;; Leiningen task.
(defn lesscss
  "Compile Less CSS resources."
  [project & args]
  (let [lesscss-paths (:lesscss-paths project (default-lesscss-paths project))
        lesscss-output-path (or (:lesscss-output-path project)
                                (:compile-path project))]
    ;; Iterate over all the Less CSS files and compile them
    (doseq [less-path lesscss-paths]
      (let [less-files (list-less-files less-path)
            errors (doall
                     (filter identity
                             (for [less-file less-files]
                               (lesscss-compile project less-path less-file lesscss-output-path))))]
        (when (not-empty errors)
          (main/abort (join "\n" errors)))))
    (when (= (first args) "auto")
      (-> (map #(watch-less-files project % lesscss-output-path) lesscss-paths)
          doall
          first
          deref))))
