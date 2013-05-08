(ns helland.ftp-deploy.core
  (:gen-class :main true)
  (:require [miner.ftp :as ftp]
            [clojure.string :as str]
            [clojure.data :as data]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:use [clojure.tools.cli :only [cli]])
  (:import [java.util Calendar]
           [java.text SimpleDateFormat]))

(defn timestamp
  []
  (let [c (Calendar/getInstance)
        f (SimpleDateFormat. "HH:mm:ss")]
    (.format f (.getTime c))))

(defn verboseln
  "Verbose debug info"
  [& strings]
  (apply println strings))

(defn verbose
  "Verbose debug info"
  [& strings]
  (apply print strings))

(defn walk-dir
  "Walks recursively through a directory returns a list of all
   java.io.File objects."
  [dir]
  (file-seq (io/file dir)))

(defn gitexception->regex
  "Takes a git exception string and returns a regex"
  [exception]
  (re-pattern (str "^" (str/replace exception #"\*" "(.*?)") "$")))

(defn except?
  "Should the filename be excepted according to exceptions?"
  [filename exceptions]
  (not (nil? (some #(re-matches (gitexception->regex %) filename) exceptions))))

(defn concat-path
  "Concatenates a lot of arguments into a string"
  [& segments]
  (subs (apply str (map (fn [seg] (if-not (#{"."} seg) (str "/" seg) seg)) segments)) 1))

(defn get-dirs
  "Retrieves a list of dirnames from topmost level to right."
  [file]
  (loop [f (if (.isDirectory file) file (.getParentFile file))
         path '()]
    (if f
      (recur (.getParentFile f) (conj path (.getName f)))
      path)))

(defn upload-file
  "Uploads the file to the destination. Must be within with-ftp."
  [ftpclient src destname]
  (let [dest (io/file destname)
        dirs (get-dirs dest)]
    (doall
     (for [dir dirs]
       (when-not (ftp/client-cd ftpclient dir)
         (verboseln "Creating directory " dir)
         (ftp/client-mkdir ftpclient dir))))
    (when (.isFile src)
      (verbose "Uploading file " src " to dest: " dest)
      (if (ftp/client-put ftpclient src destname)
        (verboseln " Success!")
        (verboseln " FAIL!")))))

(defn delete-file
  "Deletes the file from FTP:"
  [ftpclient path]
  (println "Deleting file:" path)
  (ftp/client-delete ftpclient path))

(defn walk-files
  "Walks through all files matched by settings and calls callback with
   two args: (java.io.File) src and (string) dst."
  [callback settings]
  (dorun
   (for [dir (:directories settings)
         :let [dest (second dir)]
         file (rest (walk-dir (first dir)))
         :let [dest-filename (subs (.getPath file) (count (first dir)))]
         :when (not (except? (.getName file) (:exceptions settings)))]
     (callback file (concat-path (:dest settings) dest dest-filename))))
  (dorun
   (for [rules (:files settings)
         :let [file (io/file (first rules))
               dest (second rules)]
         :when (and (not (except? (.getName file) (:exceptions settings)))
                    (.isFile file))]
     (callback file (concat-path (:dest settings) dest)))))

(defn run!
  "Runs the settings configuration"
  [settings]
  (ftp/with-ftp [client (:host settings)]
    (walk-files (partial upload-file client) settings)))

(defn watch!
  [settings]
  (let [last-check (atom 0) ; Should be 0 in production version
        indexed-files (atom #{})
        files (atom #{})
        to-update (atom #{})]
    (while true
      ;; Walk files:
      (walk-files
       (fn [file dest]
         (do (if (>= (.lastModified file) @last-check)
               (swap! to-update conj [file dest]))
             (swap! files conj [file dest])))
       settings)
      
      ;; Delete?
      (let [diff (data/diff @files @indexed-files)
            to-upload (concat (if (= @last-check 0) #{} (first diff)) @to-update)
            to-delete (second diff)]
        (when-not (and (empty? to-upload) (empty? to-delete))
          (println "Connecting to server...")
          (ftp/with-ftp [client (:host settings)]
            (dorun (map (fn [task] (upload-file client (first task) (second task))) to-upload))
            (when-not (= @last-check 0)
              (dorun (map (fn [task]
                            (do
                              (delete-file client (second task))
                              (swap! indexed-files #(remove #{task} %))))
                          to-delete))))))

      ;; Prepare for next loop.
      (reset! indexed-files @files)
      (reset! files #{})
      (reset! to-update #{})
      (reset! last-check (System/currentTimeMillis))
      (Thread/sleep 1000))))



(defn -main
  [config & args]
  (let [settings (edn/read-string (slurp (str config ".clj")))]
    (if (= (first args) "--watch")
      (watch! settings)
      (run! settings))))
