(defproject ftp-deploy "0.1.0-SNAPSHOT"
  :description "FTP deployment tool"
  :url "http://github.com/KnutHelland/ftp-deploy"
  :license {:name "GNU GPLv3 (or newer)"
            :url "http://www.gnu.org/licenses/gpl.html"}
  :main helland.ftp-deploy.core
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.cli "0.2.2"]
                 [commons-net "3.1"]])
