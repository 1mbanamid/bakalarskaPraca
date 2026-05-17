#!/bin/bash
cd "/Users/imba/iCloud Drive (архив)/Documents/Code/Bakalarska praca/dietify-master/ai-planner"
export $(grep -v '^#' .env | xargs)
mvn spring-boot:run
