package com.meowody.runtime.cocos.model.objectdata.group;

import com.meowody.runtime.cocos.model.CColor;
import com.meowody.runtime.cocos.model.FileData;
import com.meowody.runtime.cocos.model.objectdata.GroupData;

/**
 * Created by Administrator on 2017/7/21.
 */
public class ButtonObjectData extends GroupData {
    public String ButtonText;
    public CColor TextColor;
    public FileData PressedFileData;
    public FileData NormalFileData;
    public FileData DisabledFileData;
    public boolean DisplayState;
}
