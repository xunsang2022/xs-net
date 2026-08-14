package com.proto.generator.core;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * Proto Java区域键
 * 
 * @author  https://github.com/xunsang2022
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProtoJavaRegionKey {
    
    /** proto文件名 */
    private String fileName;
    
    /** proto包名 */
    private String filePackage;
} 