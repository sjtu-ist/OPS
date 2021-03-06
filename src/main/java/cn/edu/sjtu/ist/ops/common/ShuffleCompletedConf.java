/*
 * Copyright 2018 SJTU IST Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.edu.sjtu.ist.ops.common;

import com.google.gson.Gson;

public class ShuffleCompletedConf extends ShuffleConf {

    private final HadoopPath hadoopPath;

    public ShuffleCompletedConf(MapConf task, OpsNode dstNode, Integer num, HadoopPath hadoopPath) {
        super(task, dstNode, num);
        this.hadoopPath = hadoopPath;
    }

    public ShuffleCompletedConf(ShuffleConf shuffle, HadoopPath hadoopPath) {
        super(shuffle.getTask(), shuffle.getDstNode(), shuffle.getNum());
        this.hadoopPath = hadoopPath;
    }

    public HadoopPath getHadoopPath() {
        return this.hadoopPath;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}