(defproject pc "0.1.0-SNAPSHOT"
  :description "CircleCI's frontend app"
  :url "https://circleci.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [inflections "0.8.2"]

                 [compojure "1.3.1"]
                 [cheshire "5.4.0"]
                 [clj-time "0.6.0"]
                 [org.clojure/tools.nrepl "0.2.7"]
                 [javax.servlet/servlet-api "2.5"]
                 [slingshot "0.12.1"]
                 [hiccup "1.0.4"]
                 [org.clojure/tools.logging "0.3.1"]
                 [log4j "1.2.17"]
                 [log4j/apache-log4j-extras "1.1"]
                 [org.slf4j/slf4j-api "1.7.10"]
                 [org.slf4j/slf4j-log4j12 "1.7.10" :exclusions [log4j]]

                 [clj-statsd "0.3.10"]

                 [cider/cider-nrepl "0.8.2"]
                 [clj-http "1.0.1" :exclusions [commons-codec]]
                 [com.datomic/datomic-pro "0.9.5130" :exclusions [org.slf4j/slf4j-nop
                                                                  org.slf4j/slf4j-api
                                                                  com.amazonaws/aws-java-sdk]]
                 [org.postgresql/postgresql "9.4-1200-jdbc41" :exclusions [org.slf4j/slf4j-simple]]

                 [amazonica "0.3.12"]

                 [ring/ring "1.3.2" :exclusions [hiccup
                                                 org.clojure/java.classpath]]
                 ;; uses a url-safe token
                 [dwwoelfel/ring-anti-forgery "1.0.0-cbd219138abf4e9916a51caa7629c357b5d164af" :exclusions [hiccup]]
                 [http-kit "2.1.18-c9c0b155a4ab05630d332a7d2da0aaf433889772"]
                 [com.taoensso/sente "1.4.0-alpha2" :exclusions [http-kit]]
                 [clj-stacktrace "0.2.8"]

                 [org.clojure/tools.reader "0.8.13"]
                 [com.google.guava/guava "18.0"]

                 [schejulure "1.0.1"]

                 [org.clojars.pallix/batik "1.7.0"]

                 ;; needed to make lein pedantic happy
                 [org.codehaus.plexus/plexus-utils "2.0.6"]
                 [com.cemerick/pomegranate "0.3.0"  :exclusions [org.codehaus.plexus/plexus-utils]]

                 [com.novemberain/pantomime "2.3.0"]

                 [crypto-equality "1.0.0"]

                 [fs "0.11.1"]

                 [datascript "0.8.1"]

                 [ankha "0.1.4"]
                 [org.clojure/clojurescript "0.0-2760"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [cljs-ajax "0.2.6"]

                 ;; Use yaks/om for the pattern tag (it's in React,
                 ;; but not Om yet)
                 ;;[om "0.6.4"]

                 [precursorapp/react "0.12.2-7-new-tags"]

                 [sablono "0.3.1" :exclusions [cljsjs/react]]
                 [secretary "1.2.1"]
                 [com.andrewmcveigh/cljs-time "0.2.4"]
                 [com.cemerick/url "0.1.1"]
                 [hiccups "0.3.0"]

                 [weasel "0.5.0"] ;; repl

                 ;; needed to make lein pedantic happy
                 [commons-codec "1.6"]
                 [figwheel "0.2.3-SNAPSHOT" :exclusions [org.codehaus.plexus/plexus-utils
                                                         commons-codec]]

                 ;; Frontend tests
                 [com.cemerick/clojurescript.test "0.3.0"]]

  :repositories ^:replace [["prcrsr-s3-releases" {:url "s3p://prcrsr-jars/releases"
                                                  :sign-releases false ;; TODO put a gpg key on CI
                                                  :username [:gpg
                                                             :env/prcrsr_jars_username
                                                             "AKIAIQXSSVZAOLOC5KZA"]
                                                  :passphrase [:gpg
                                                               :env/prcrsr_jars_password
                                                               "VgIfEWLuHOIMtSvOKm5q00t/XjGgsok8AvNIcNhq"]
                                                  :snapshots false}]

                           ["prcrsr-s3-snapshots" {:url "s3p://prcrsr-jars/snapshots"
                                                   :sign-releases false ;; TODO put a gpg key on CI
                                                   :username [:gpg
                                                              :env/prcrsr_jars_username
                                                              "AKIAIQXSSVZAOLOC5KZA"]
                                                   :passphrase [:gpg
                                                                :env/prcrsr_jars_password
                                                                "VgIfEWLuHOIMtSvOKm5q00t/XjGgsok8AvNIcNhq"]
                                                   :snapshots true}]
                           ["central" {:url "https://repo1.maven.org/maven2/" :snapshots false}]
                           ["clojars" {:url "https://clojars.org/repo/"}]]

  :figwheel {:http-server-root "public"
             :server-port 3448
             :css-dirs ["resources/public/css"]}

  :plugins [[lein-cljsbuild "1.0.4"]
            [com.cemerick/austin "0.1.6"]
            [lein-figwheel "0.2.3-SNAPSHOT" :exclusions [org.codehaus.plexus/plexus-utils
                                                         commons-codec]]
            [circle/lein-deploy-deps "0.1.3"]
            [circle/s3-wagon-private "1.2.2" :exclusions [commons-codec org.apache.httpcomponents/httpclient]]]

  :exclusions [[org.clojure/clojure]
               [org.clojure/clojurescript]
               [org.slf4j/log4j-over-slf4j]]

  :pedantic? :abort

  :main ^:skip-aot pc.init

  :jar-name "pc.jar"
  :uberjar-name "pc-standalone.jar"

  :jvm-opts ["-Djava.net.preferIPv4Stack=true"
             "-server"
             "-XX:MaxPermSize=256m"
             "-XX:+UseConcMarkSweepGC"
             "-Xss1m"
             "-Xmx1024m"
             "-XX:+CMSClassUnloadingEnabled"
             "-Dfile.encoding=UTF-8"]


  :repl-options {:init-ns pc.repl}

  :clean-targets ^{:protect false} [:target-path "resources/public/cljs/"]

  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src-cljs"
                                       "dev-cljs"
                                       "yaks/om/src"]
                        :compiler {:output-to "resources/public/cljs/out/frontend-dev.js"
                                   :output-dir "resources/public/cljs/out"
                                   :optimizations :none
                                   :source-map "resources/public/cljs/out/sourcemap-frontend.map"}}
                       {:id "production"
                        :source-paths ["src-cljs" "yaks/om/src"]
                        :compiler {:pretty-print false
                                   :output-to "resources/public/cljs/production/frontend.js"
                                   :output-dir "resources/public/cljs/production"
                                   :output-wrapper false
                                   :optimizations :advanced
                                   :externs ["src-cljs/js/react-externs.js"
                                             "src-cljs/js/analytics-externs.js"]
                                   :source-map "resources/public/cljs/production/sourcemap-frontend.map"}}]})
