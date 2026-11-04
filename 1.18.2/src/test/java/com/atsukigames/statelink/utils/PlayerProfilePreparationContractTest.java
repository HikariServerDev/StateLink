package com.atsukigames.statelink.utils;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Guard the actual preparation bytecode, not a separate model of profile validation. */
class PlayerProfilePreparationContractTest {
    @Test void loginValidationDoesNotReadTheRenderedScoreboardName() throws IOException {
        var preparation = inspectPreparation();
        assertFalse(preparation.calls().stream().anyMatch(call ->
            call.endsWith(".getDisplayName") || call.endsWith(".getTeam")),
            "backend-local team/prefix/suffix labels are not login identity");
    }

    @Test void preparationCannotTakeTheStoredDisplayLabelAsAnIdentityInput() throws IOException {
        assertEquals(2, Type.getArgumentTypes(inspectPreparation().descriptor()).length,
            "preparation takes the player and profile properties, not display_name");
    }

    @Test void preparationStillChecksTheServerThreadAndDecodesTheLoginIdentity() throws IOException {
        var calls = inspectPreparation().calls();
        assertTrue(calls.contains("com/atsukigames/statelink/utils/PlayerProfileCodec.onServerThread"));
        assertTrue(calls.contains("com/atsukigames/statelink/utils/PlayerProfileCodec.decode"));
    }

    private record Preparation(String descriptor, List<String> calls) {}

    private Preparation inspectPreparation() throws IOException {
        var preparations = new ArrayList<Preparation>();
        try (var bytes = getClass().getResourceAsStream("PlayerProfileCodec.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (!name.equals("prepare")) return null;
                    var calls = new ArrayList<String>();
                    preparations.add(new Preparation(descriptor, calls));
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                String descriptor, boolean isInterface) {
                            calls.add(owner + "." + method);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals(1, preparations.size());
        return preparations.get(0);
    }
}
